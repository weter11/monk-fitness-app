package com.monkfitness.app

import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.CameraFraming
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **C1 — the viewport containment invariant: every frame of every production pose is drawn WHOLE on
 * the surface it is drawn on.**
 *
 * ## The defect this pins (measured, on the production path)
 *
 * `Camera` maps world units onto pixels one-for-one (times the pose's authored zoom) and anchors the
 * world origin at a hardcoded `centerY = 0.7`, so a pose's drawn size is *independent of the
 * surface*. The authored zoom (`1.2`-`1.3`) is composed for a tall canvas: at the hero canvas the
 * app actually uses (`ExerciseHeroMedia`, `861x531` px) a standing pose — and any pose with the arms
 * overhead — is taller than the surface and loses its head off the top edge. Measured with the
 * authored camera over this file's own sweep (every production pose × `5` phases × `5` view angles
 * from straight ahead to the `+-90`° the rotation gesture allows):
 *
 * | canvas | frames clipped | poses |
 * |---|---|---|
 * | `861x531` (the hero canvas) | 1282 of 2805 | 26 of 51 |
 * | `1280x720` | 854 of 2805 | 22 |
 * | `1440x700` | 885 of 2805 | 22 |
 * | `1000x1000` | 15 of 2805 | 2 |
 * | `1080x1920` | 0 of 2805 | 0 |
 *
 * — i.e. the poses are fine and the framing is not: the same frames that clip a phone-sized canvas
 * pass an oversized one, which is why the defect survived a suite whose only viewport was oversized.
 *
 * ## The rule under test
 *
 * [CameraFraming] derives the frame from the frame's own drawn bounds: **the zoom is the largest one,
 * not exceeding the pose's authored zoom, at which the whole drawn silhouette lies inside the
 * viewport, anchored where the pose anchored it.** The silhouette measured here is the one the
 * renderers actually stroke — see [silhouette] — so the invariant is checked against what is drawn,
 * not against a model of it.
 *
 * ## What each test is for
 *
 *  * [everyFrameOfEveryPoseIsDrawnWholeOnTheRealHeroCanvas] — the gate: containment for the whole
 *    corpus on the hero canvas and on two other realistic surfaces, at every reachable view angle.
 *  * [thePosesTheAuthoredFrameClipsAreDrawnWholeAtEveryPhaseOfTheirRep] — the previously-clipped
 *    standing poses, densely (`51` phases × both frame conditions), so a frame that only fits at the
 *    sampled phases cannot hide.
 *  * [theAuthoredFrameReallyDoesClip] — the non-vacuity anchor: the pre-C1 path must still measure as
 *    clipping, the clipped inventory is pinned by name, and the oversized canvases the old suite used
 *    must still measure as fitting (that is why the defect survived).
 *  * [framesThatAlreadyFitAreLeftBitIdentical] — no regression: any frame whose authored silhouette
 *    already fits is projected bit-identically, with the wide/supine/prone families named explicitly.
 *  * [anOversizedCanvasIsUnaffected] — the surfaces the old suite used are unchanged.
 *  * [theFitIsPureIdempotentAndOnlyEverShrinks] — the fit is a fixed point measured against the
 *    AUTHORED zoom, so it cannot ratchet down as a rep's frames are drawn, and never enlarges.
 *  * [theViewpointRemainsThePosesOwn] — the fit owns the scale alone: yaw, pitch and the anchor stay
 *    exactly what `PoseMetadata.camera` authored (`docs/ENGINE.md` §10).
 *
 * ## Deliberately NOT asserted
 *
 * The ground grid, the contact shadows and the environment props (box, step, bench, wall) are the
 * floor and the pose's furniture, laid out to a horizon that leaves the frame by construction — they
 * are not the subject, and C1 does not move them. How *large* a full-standing pose should read on a
 * very small canvas (here it is shrunk to fit, which is containment but not composition) is a
 * presentation decision this instrument does not take.
 */
class ViewportFramingInvariantTest {

    private val engine = SkeletonEngine(DEF, STYLE)
    private val projector = SkeletonProjector()
    private val framing = CameraFraming(engine, SETTINGS)
    private val compensator = ScreenSpaceCompensation(SETTINGS)

    /**
     * The hero canvas `ExerciseHeroMedia` gives the skeleton: `861x531` px, the surface the framing
     * audit measured. The other entries are the same component on a small and a laptop-sized screen;
     * containment is asserted on all three.
     */
    private val realisticCanvases = listOf(HERO, 411f to 225f, 1280f to 720f)

    /** Surfaces at or above the authored composition: every frame already fits there. */
    private val oversizedCanvases = listOf(1000f to 1000f, 1080f to 1920f)

    /** Containment is closed-form, so the only slack is float noise at the boundary. */
    private val pixelSlack = 0.01f

    // ------------------------------------------------------------------------------------------
    // The production view setup
    // ------------------------------------------------------------------------------------------

    /**
     * The view setup after C1, exactly as `SkeletonRenderer`'s canvas block performs it: the pipeline
     * publishes the frame, [CameraFraming] derives the frame's zoom from its own drawn bounds, and the
     * projector maps it through the camera.
     */
    private fun productionView(camera: Camera, pose: SkeletonPose, width: Float, height: Float): ProjectedSkeleton {
        camera.zoom = framing.frameZoom(camera, pose, width, height)
        return authoredView(camera, pose, width, height)
    }

    /**
     * The view setup BEFORE C1 — the pose's authored camera, projected as authored. This file's
     * non-vacuity and no-regression instruments measure against it.
     */
    private fun authoredView(camera: Camera, pose: SkeletonPose, width: Float, height: Float): ProjectedSkeleton {
        val buffer = ProjectedSkeleton()
        projector.project(pose, camera, engine, width, height, buffer, pose.environment.ground.level)
        return buffer
    }

    private fun cameraOf(sample: Sample, zoom: Float? = null): Camera =
        Camera(sample.cameraDefinition).also {
            it.yaw = sample.cameraDefinition.defaultYaw + sample.yawOffset
            if (zoom != null) it.zoom = zoom
        }

    // ------------------------------------------------------------------------------------------
    // The drawn silhouette — the instrument
    // ------------------------------------------------------------------------------------------

    /**
     * The bounds of everything the renderers actually stroke, read off the buffers they iterate:
     *
     *  * **bones** — `SkeletonRenderer` 127-128 (`drawLinearBone(..., item.thickness + outline * 2f,
     *    …)` over `item.thickness = b.thickness * scaleBuffer.thicknessScale`) and
     *    `SkeletonSnapshotRenderer` 337-345; the drawn half-width is `thickness / 2 + outline`, where
     *    `outline = style.outlineWidth * scaleBuffer.outlineScale`;
     *  * **indicator discs** — `SkeletonRenderer` 134-135 / `SkeletonSnapshotRenderer` 356-360
     *    (`drawCircle(..., item.radius + outline, …)` with `outline = 2f * scaleBuffer.outlineScale`);
     *  * **torso faces** — `SkeletonRenderer` 150 (`drawPath(path, strokeC, Stroke(width =
     *    style.outlineWidth))`), a stroke centred on the path edge.
     *
     * The ground grid, the contact shadows and the environment props are deliberately excluded: they
     * are the floor and the pose's furniture, not the subject (see the class KDoc).
     */
    private fun silhouette(buffer: ProjectedSkeleton, zoom: Float): FloatArray {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val scale = ScreenSpaceScale()

        fun include(x0: Float, x1: Float, y0: Float, y1: Float) {
            if (x0 < minX) minX = x0
            if (x1 > maxX) maxX = x1
            if (y0 < minY) minY = y0
            if (y1 > maxY) maxY = y1
        }

        for (i in 0 until buffer.boneCount) {
            val bone = buffer.bones[i]
            compensator.computeScale(bone.p1, zoom, scale)
            val half = bone.thickness * scale.thicknessScale * 0.5f
            val outline = STYLE.outlineWidth * scale.outlineScale
            include(
                minOf(bone.p1.x, bone.p2.x) - half - outline, maxOf(bone.p1.x, bone.p2.x) + half + outline,
                minOf(bone.p1.y, bone.p2.y) - half - outline, maxOf(bone.p1.y, bone.p2.y) + half + outline
            )
        }

        for (indicator in buffer.indicators) {
            compensator.computeScale(indicator.point, zoom, scale)
            val radius = (if (indicator.id == Joint.HEAD_POS) STYLE.headRadius else STYLE.jointRadius) *
                scale.radiusScale + 2f * scale.outlineScale
            include(
                indicator.point.x - radius, indicator.point.x + radius,
                indicator.point.y - radius, indicator.point.y + radius
            )
        }

        for (i in 0 until buffer.faceCount) {
            val half = STYLE.outlineWidth * 0.5f
            for (p in buffer.faces[i].points) {
                include(p.x - half, p.x + half, p.y - half, p.y + half)
            }
        }

        return floatArrayOf(minX, maxX, minY, maxY)
    }

    /** How far the drawn silhouette leaves the canvas, per edge (negative = inside), in pixels. */
    private fun overflow(buffer: ProjectedSkeleton, zoom: Float, width: Float, height: Float): FloatArray {
        val b = silhouette(buffer, zoom)
        return floatArrayOf(-b[0], b[1] - width, -b[2], b[3] - height)
    }

    /** True when the whole drawn silhouette is inside the canvas. */
    private fun contained(buffer: ProjectedSkeleton, zoom: Float, width: Float, height: Float): Boolean =
        overflow(buffer, zoom, width, height).all { it <= pixelSlack }

    private fun describe(sample: Sample, canvas: Pair<Float, Float>, zoom: Float, out: FloatArray): String =
        "C1 viewport containment: ${sample.pose} at progress=${sample.progress}, yawOffset=${sample.yawOffset}, " +
            "zoom=$zoom on a ${canvas.first}x${canvas.second} canvas leaves the frame by " +
            "left=${out[0]} right=${out[1]} top=${out[2]} bottom=${out[3]} px"

    /** Bit-exact comparison of two projections — the strongest form of "this frame did not move". */
    private fun assertSameProjection(expected: ProjectedSkeleton, actual: ProjectedSkeleton, label: String) {
        for (joint in Joint.entries) {
            assertEquals(
                "$label: ${joint.name}.x moved",
                java.lang.Float.floatToIntBits(expected.joints[joint.index].x),
                java.lang.Float.floatToIntBits(actual.joints[joint.index].x)
            )
            assertEquals(
                "$label: ${joint.name}.y moved",
                java.lang.Float.floatToIntBits(expected.joints[joint.index].y),
                java.lang.Float.floatToIntBits(actual.joints[joint.index].y)
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1 — the gate
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyFrameOfEveryPoseIsDrawnWholeOnTheRealHeroCanvas() {
        assertTrue("the corpus sweep is empty", SWEEP.isNotEmpty())
        var evaluations = 0

        for (canvas in realisticCanvases) {
            for (sample in SWEEP) {
                val camera = cameraOf(sample)
                val buffer = productionView(camera, sample.frame, canvas.first, canvas.second)
                val out = overflow(buffer, camera.zoom, canvas.first, canvas.second)
                evaluations++
                assertTrue(describe(sample, canvas, camera.zoom, out), out.all { it <= pixelSlack })
            }
        }

        // non-vacuity: every pose × phase × view angle × canvas was actually measured
        assertEquals(
            "every corpus sample must be evaluated on every realistic canvas",
            productionPoseClasses().size * SAMPLES.size * YAW_OFFSETS.size * realisticCanvases.size,
            evaluations
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2 — the previously-clipped standing poses, densely
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePosesTheAuthoredFrameClipsAreDrawnWholeAtEveryPhaseOfTheirRep() {
        val densePhases = (0 until 51).map { it / 50f }
        var evaluations = 0

        for (name in DENSELY_SWEPT_POSES) {
            for (condition in FrameCondition.entries) {
                val advancing = SkeletonPipeline(DEF)
                for (progress in densePhases) {
                    val frame = when (condition) {
                        // COLD — a genuinely cold first frame: a fresh pose on a fresh pipeline.
                        FrameCondition.COLD -> publishedFrameOf(name, SkeletonPipeline(DEF), progress)
                        // PLAYING — one publisher advancing 0 -> 1 across the rep.
                        FrameCondition.PLAYING -> publishedFrameOf(name, advancing, progress)
                    }
                    val sample = Sample(name, progress, 0f, frame, MotionProbe.build(name).metadata.camera)
                    val camera = cameraOf(sample)
                    val buffer = productionView(camera, frame, HERO.first, HERO.second)
                    val out = overflow(buffer, camera.zoom, HERO.first, HERO.second)
                    evaluations++
                    assertTrue(
                        describe(sample, HERO, camera.zoom, out) + " ($condition)",
                        out.all { it <= pixelSlack }
                    )
                }
            }
        }

        assertEquals(
            "every densely swept pose × phase × frame condition must be evaluated",
            DENSELY_SWEPT_POSES.size * densePhases.size * FrameCondition.entries.size,
            evaluations
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3 — the defect is real (the non-vacuity anchor and the clipped inventory)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theAuthoredFrameReallyDoesClip() {
        val clippedPoses = mutableSetOf<String>()
        var clippedFrames = 0

        for (sample in SWEEP) {
            val camera = cameraOf(sample)
            val buffer = authoredView(camera, sample.frame, HERO.first, HERO.second)
            if (!contained(buffer, camera.zoom, HERO.first, HERO.second)) {
                clippedPoses.add(sample.pose)
                clippedFrames++
            }
        }

        assertEquals(
            "the set of production poses the AUTHORED frame clips on the hero canvas moved — re-measure the " +
                "inventory (docs/STABILIZATION_AUDIT.md) before adjusting this pin: it is the defect's own " +
                "record, not a tolerance",
            CLIPPED_AT_HERO, clippedPoses
        )
        assertTrue("no clipped frame measured — the instrument is blind", clippedFrames > 0)

        // and the surfaces the old suite used are exactly why this defect survived: the authored frame fits
        // there. 1080x1920 fits every published frame of the whole corpus.
        for (sample in SWEEP) {
            val camera = cameraOf(sample)
            val buffer = authoredView(camera, sample.frame, 1080f, 1920f)
            assertTrue(
                describe(sample, 1080f to 1920f, camera.zoom, overflow(buffer, camera.zoom, 1080f, 1920f)),
                contained(buffer, camera.zoom, 1080f, 1920f)
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4 — no regression: frames that already fit are left bit-identical
    // ------------------------------------------------------------------------------------------

    @Test
    fun framesThatAlreadyFitAreLeftBitIdentical() {
        var compared = 0

        for (canvas in realisticCanvases + oversizedCanvases) {
            for (sample in SWEEP) {
                val authoredCamera = cameraOf(sample)
                val authored = authoredView(authoredCamera, sample.frame, canvas.first, canvas.second)
                if (!contained(authored, authoredCamera.zoom, canvas.first, canvas.second)) continue

                val fittedCamera = cameraOf(sample)
                val fitted = productionView(fittedCamera, sample.frame, canvas.first, canvas.second)

                assertEquals(
                    "${sample.pose} at progress=${sample.progress}, yawOffset=${sample.yawOffset} already fits " +
                        "the ${canvas.first}x${canvas.second} canvas — the fit must not move it",
                    java.lang.Float.floatToIntBits(authoredCamera.zoom),
                    java.lang.Float.floatToIntBits(fittedCamera.zoom)
                )
                assertSameProjection(
                    authored, fitted,
                    "C1 moved a frame that already fit: ${sample.pose} progress=${sample.progress} " +
                        "yawOffset=${sample.yawOffset} canvas=${canvas.first}x${canvas.second}"
                )
                compared++
            }
        }

        assertTrue("no already-fitting frame found — the instrument is blind", compared > 0)

        // the families the mission names explicitly, asserted by name on the hero canvas
        for (name in WIDE_SUPINE_PRONE_FAMILIES) {
            val sample = SWEEP.first { it.pose == name && it.progress == 0.5f && it.yawOffset == 0f }
            val authoredCamera = cameraOf(sample)
            val authored = authoredView(authoredCamera, sample.frame, HERO.first, HERO.second)
            assertTrue(
                "$name must already fit the hero canvas (it did before C1) — measured " +
                    overflow(authored, authoredCamera.zoom, HERO.first, HERO.second).toList(),
                contained(authored, authoredCamera.zoom, HERO.first, HERO.second)
            )
            val fittedCamera = cameraOf(sample)
            val fitted = productionView(fittedCamera, sample.frame, HERO.first, HERO.second)
            assertEquals(
                "$name must be left at its authored zoom",
                java.lang.Float.floatToIntBits(authoredCamera.zoom),
                java.lang.Float.floatToIntBits(fittedCamera.zoom)
            )
            assertSameProjection(authored, fitted, "$name must be projected bit-identically")
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5 — the surfaces the old suite used are unaffected
    // ------------------------------------------------------------------------------------------

    @Test
    fun anOversizedCanvasIsUnaffected() {
        for (canvas in oversizedCanvases) {
            var identities = 0
            var clippedByAuthoredFrame = 0
            for (sample in SWEEP) {
                val authoredCamera = cameraOf(sample)
                val authored = authoredView(authoredCamera, sample.frame, canvas.first, canvas.second)
                if (!contained(authored, authoredCamera.zoom, canvas.first, canvas.second)) {
                    clippedByAuthoredFrame++
                }

                val closing = cameraOf(sample)
                val zoom = framing.frameZoom(closing, sample.frame, canvas.first, canvas.second)
                val fitted = authoredView(cameraOf(sample, zoom), sample.frame, canvas.first, canvas.second)
                val out = overflow(fitted, zoom, canvas.first, canvas.second)
                assertTrue(describe(sample, canvas, zoom, out), out.all { it <= pixelSlack })
                if (zoom == sample.cameraDefinition.defaultZoom) identities++
            }

            assertEquals(
                "on a ${canvas.first}x${canvas.second} canvas the fit must be the identity for exactly the " +
                    "frames the AUTHORED frame already fits",
                SWEEP.size - clippedByAuthoredFrame, identities
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 6 — the fit is pure: idempotent, never enlarging, never history-dependent
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFitIsPureIdempotentAndOnlyEverShrinks() {
        for (sample in SWEEP) {
            val authored = sample.cameraDefinition.defaultZoom
            val zoom1 = framing.frameZoom(cameraOf(sample), sample.frame, HERO.first, HERO.second)
            assertTrue(
                "${sample.pose} at progress=${sample.progress}: the fit must never exceed the authored zoom " +
                    "($zoom1 > $authored)",
                zoom1 <= authored
            )

            // fixed point: the fitted frame is already contained, so framing it again changes nothing
            assertEquals(
                "${sample.pose} at progress=${sample.progress}: the fit must be idempotent",
                java.lang.Float.floatToIntBits(zoom1),
                java.lang.Float.floatToIntBits(
                    framing.frameZoom(cameraOf(sample, zoom1), sample.frame, HERO.first, HERO.second)
                )
            )

            // history independence: a camera that already carries another zoom frames identically
            assertEquals(
                "${sample.pose} at progress=${sample.progress}: the fit must not depend on the camera's history",
                java.lang.Float.floatToIntBits(zoom1),
                java.lang.Float.floatToIntBits(
                    framing.frameZoom(cameraOf(sample, authored * 0.25f), sample.frame, HERO.first, HERO.second)
                )
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 7 — the fit owns the scale alone
    // ------------------------------------------------------------------------------------------

    @Test
    fun theViewpointRemainsThePosesOwn() {
        for (sample in SWEEP) {
            val camera = cameraOf(sample)
            val authoredZoom = camera.zoom
            camera.zoom = framing.frameZoom(camera, sample.frame, HERO.first, HERO.second)
            assertEquals(
                "the fit may not touch the pitch",
                sample.cameraDefinition.defaultPitch.toDouble(), camera.pitch.toDouble(), 0.0
            )
            assertEquals(
                "the fit may not touch the yaw (the rotation gesture owns it)",
                (sample.cameraDefinition.defaultYaw + sample.yawOffset).toDouble(), camera.yaw.toDouble(), 0.0
            )
            assertEquals("the fit may not touch the vertical anchor", 0.7f.toDouble(), camera.centerY.toDouble(), 0.0)
            assertEquals("the fit may not touch the horizontal anchor", 0.5f.toDouble(), camera.centerX.toDouble(), 0.0)
            assertTrue("the fit may not enlarge", camera.zoom <= authoredZoom)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Corpus, sweep and pins
    // ------------------------------------------------------------------------------------------

    /** One sampled frame of the corpus, captured BY VALUE (the pipeline reuses its output buffer). */
    private class Sample(
        val pose: String,
        val progress: Float,
        val yawOffset: Float,
        val frame: SkeletonPose,
        val cameraDefinition: CameraDefinition
    )

    private enum class FrameCondition { COLD, PLAYING }

    private companion object {
        val DEF = SkeletonDefinition.DEFAULT_ADULT
        val STYLE = SkeletonStyle.DEFAULT
        val SETTINGS = ScreenSpaceSettings.DEFAULT

        /** The hero canvas the framing audit measured (`ExerciseHeroMedia`). */
        val HERO = 861f to 531f

        /** The five phases of a rep the sibling corpus invariants sample. */
        val SAMPLES = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /**
         * The view angles the app can reach: straight ahead, the `+-45`° the drag gesture crosses first,
         * and the `+-90`° it clamps at (`AnimationController.onRotate`).
         */
        val YAW_OFFSETS = listOf(0f, 0.7854f, -0.7854f, 1.5708f, -1.5708f)

        /**
         * The production poses the AUTHORED frame clips on the hero canvas, measured — the inventory of
         * the defect this gate was built for, not a list of exceptions (the fit is per-bound and applies
         * to all poses identically).
         *
         * **Re-measured by the animation-coverage phase, batch 1** (`feat/animation-coverage-01`): the four
         * pose classes the batch added (`AnkleMobilityPose`, `CalfStretchPose`, `HorseStancePose`,
         * `WallSitPose`) join the inventory, like 26 of the 49 pre-existing production poses. They carry the
         * family's own `CameraDefinition`; NO camera or framing behaviour is changed by the phase, so this is
         * the defect's record extended by four more authored frames.
         *
         * **Re-measured by the animation-coverage phase, batch 2** (`feat/animation-coverage-02`, off the
         * #258 merge `6887738`): three of the four pose classes the batch added (`BandPullApartPose`,
         * `DipsPose`, `RowsPose` — the standing banded sweep and the two bar-supported members, whose authored
         * cameras are the standing/plank families' own) join the inventory; the fourth (`YTRaisesPose`, the
         * prone drill — the family's flat layout) fits the hero canvas as authored, so the inventory is
         * `30 → 33` frames. Again: NO camera or framing behaviour is changed by the phase.
         */
        val CLIPPED_AT_HERO = setOf(
            "AirSquatPose", "AlternatingForwardLungesPose", "AlternatingReverseLungesPose",
            "AlternatingSideLungesPose", "AnkleMobilityPose", "ArmCirclesPose", "BandPullApartPose",
            "BurpeePose", "CalfStretchPose", "CossackSquatPose", "CouchStretchPose", "DipsPose",
            "DynamicWorldsGreatestStretchPose", "FacePullPose", "HalfKneelingStretchPose", "HangPose",
            "HipCarsPose", "HorseStancePose", "JumpSquatPose", "KettlebellSwingPose",
            "NeutralGripPullUpPose", "RowsPose", "ScapularPullUpPose", "ScapularRetractionPose",
            "SquatPose", "StandardPullUpPose", "StepUpPose", "SumoSquatPose", "ThoracicExtensionPose",
            "UnderhandChinUpPose", "WallSitPose", "WallSlidesPose", "WideGripPullUpPose"
        )

        /**
         * The previously-clipped poses the dense sweep covers: the worst offenders of the inventory at
         * each axis (overhead reach, floor-anchored standing, the widest stance, the step).
         */
        val DENSELY_SWEPT_POSES = listOf(
            "ArmCirclesPose", "StandardPullUpPose", "WallSlidesPose", "StepUpPose", "SquatPose",
            "CossackSquatPose"
        )

        /**
         * The wide / supine / prone families the mission requires to be unaffected. They fit the hero
         * canvas as authored, so C1 must leave them bit-identical.
         */
        val WIDE_SUPINE_PRONE_FAMILIES = listOf(
            "StandardPushUpPose", "WidePushUpPose", "KneePushUpPose", "MilitaryPushUpPose",
            "SupermanPose", "ReverseSnowAngelPose", "DeadBugPose", "LegRaisePose", "GluteBridgePose",
            "PelvicTiltPose", "BirdDogPose", "StaticBirdDogHoldPose", "AlternatingBirdDogPose",
            "CatCowPose", "ProneCobraStretchPose", "MountainClimberPose"
        )

        /**
         * The corpus sweep, built once for the whole class: every production pose × every sampled phase
         * × every reachable view angle, each frame captured by value.
         */
        val SWEEP: List<Sample> by lazy {
            val samples = ArrayList<Sample>()
            for (name in productionPoseClasses()) {
                val definition = MotionProbe.build(name).metadata.camera
                for (progress in SAMPLES) {
                    val frame = publishedFrameOf(name, SkeletonPipeline(DEF), progress)
                    for (yawOffset in YAW_OFFSETS) {
                        samples.add(Sample(name, progress, yawOffset, frame, definition))
                    }
                }
            }
            samples
        }

        /** The production renderer's path: publish the frame against the pose's own Frame Context. */
        fun publishedFrameOf(name: String, pipeline: SkeletonPipeline, progress: Float): SkeletonPose {
            val metadata = MotionProbe.build(name).metadata
            return SkeletonPose().apply {
                copyFrom(
                    pipeline.produceFrame(
                        MotionProbe.build(name).build(contextFor(progress)),
                        metadata.environment,
                        metadata.support.supportPoints
                    ).pose
                )
            }
        }

        fun contextFor(progress: Float) = PoseContext(
            progress = progress, side = Side.RIGHT, definition = DEF, deltaTime = 0.0166f, cycleDuration = 2500f
        )

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
