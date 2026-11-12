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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Diagnostic for the report: measures the rig's UNION projected bounding box over a whole animation
 * cycle, at every reachable yaw, at the hero's aspect ratio, for standing / lying / wide-range cases.
 * Also writes the numbers to build/geometry-report.json so the report quotes measured values.
 */
class HeroBoundingBoxDiagnosticTest {

    private val engine = SkeletonEngine(SkeletonDefinition.DEFAULT_ADULT, SkeletonStyle.DEFAULT)
    private val projector = SkeletonProjector()
    private val compensator = ScreenSpaceCompensation(ScreenSpaceSettings.DEFAULT)
    private val framing = CameraFraming(engine, ScreenSpaceSettings.DEFAULT)
    private val style = SkeletonStyle.DEFAULT

    private val cases = mapOf(
        "standing" to listOf("AirSquatPose", "SquatPose", "StepUpPose", "StandardPullUpPose", "WallSlidesPose"),
        "lying" to listOf("DeadBugPose", "GluteBridgePose", "LegRaisePose", "SupermanPose", "ReverseSnowAngelPose"),
        "wide-range" to listOf("BurpeePose", "CossackSquatPose", "StandardPushUpPose", "AlternatingForwardLungesPose", "MountainClimberPose")
    )
    private val phases = (0..32).map { it / 32f }
    private val yawOffsets = listOf(-1.5708f, -0.7854f, 0f, 0.7854f, 1.5708f)

    /** The hero canvas the framing audit and the device (325 dp @ 2.65) use: 861x531 px. */
    private val heroW = 861f
    private val heroH = 531f

    private fun drawnBox(buffer: ProjectedSkeleton, zoom: Float): FloatArray {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var stroke = 0f
        val scale = ScreenSpaceScale()
        for (i in 0 until buffer.boneCount) {
            val b = buffer.bones[i]; compensator.computeScale(b.p1, zoom, scale)
            val half = b.thickness * scale.thicknessScale * 0.5f
            minX = min(minX, minOf(b.p1.x, b.p2.x) - half); maxX = max(maxX, maxOf(b.p1.x, b.p2.x) + half)
            minY = min(minY, minOf(b.p1.y, b.p2.y) - half); maxY = max(maxY, maxOf(b.p1.y, b.p2.y) + half)
            stroke = max(stroke, style.outlineWidth * scale.outlineScale)
        }
        for (j in buffer.indicators) {
            compensator.computeScale(j.point, zoom, scale)
            val r = (if (j.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) * scale.radiusScale
            minX = min(minX, j.point.x - r); maxX = max(maxX, j.point.x + r)
            minY = min(minY, j.point.y - r); maxY = max(maxY, j.point.y + r)
            stroke = max(stroke, 2f * scale.outlineScale)
        }
        for (i in 0 until buffer.faceCount) for (p in buffer.faces[i].points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
            stroke = max(stroke, style.outlineWidth * 0.5f * scale.outlineScale)
        }
        for (p in buffer.shadowPoints) {
            compensator.computeScale(p, zoom, scale)
            val rx = style.shadowRadiusX * scale.shadowScale; val ry = style.shadowRadiusY * scale.shadowScale
            minX = min(minX, p.x - rx); maxX = max(maxX, p.x + rx)
            minY = min(minY, p.y - ry); maxY = max(maxY, p.y + ry)
        }
        return floatArrayOf(minX - stroke, maxX + stroke, minY - stroke, maxY + stroke)
    }

    private fun published(name: String, p: Float, side: Side, pipeline: SkeletonPipeline): SkeletonPose =
        SkeletonPose().apply {
            copyFrom(pipeline.produceFrame(MotionProbe.build(name), PoseContext(progress = p, side = side, definition = SkeletonDefinition.DEFAULT_ADULT)).pose)
        }

    @Test
    fun measureRigBoundingBoxes() {
        val lines = StringBuilder()
        lines.append("HERO_ASPECT ${heroW.toInt()}x${heroH.toInt()}\n")
        val json = StringBuilder("{\n  \"hero\": [${heroW.toInt()}, ${heroH.toInt()}],\n  \"cases\": {\n")
        var firstCat = true
        for ((category, names) in cases) {
            if (!firstCat) json.append(",\n"); firstCat = false
            json.append("    \"$category\": {\n")
            var first = true
            for (name in names) {
                val def = MotionProbe.build(name).metadata.camera
                val sides = if (name in setOf("StaticBirdDogHoldPose", "AlternatingBirdDogPose")) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
                val frame = framing.exerciseFrame(Camera(def), MotionProbe.build(name), sides, heroW, heroH)
                var uMinX = Float.MAX_VALUE; var uMaxX = -Float.MAX_VALUE
                var uMinY = Float.MAX_VALUE; var uMaxY = -Float.MAX_VALUE
                for (side in sides) {
                    val pipeline = SkeletonPipeline(SkeletonDefinition.DEFAULT_ADULT)
                    for (p in phases) {
                        val pose = published(name, p, side, pipeline)
                        for (off in yawOffsets) {
                            val cam = Camera(def); cam.yaw = def.defaultYaw + off; frame.applyTo(cam)
                            val buf = ProjectedSkeleton()
                            projector.project(pose, cam, engine, heroW, heroH, buf, pose.environment.ground.level)
                            val b = drawnBox(buf, cam.zoom)
                            uMinX = min(uMinX, b[0]); uMaxX = max(uMaxX, b[1])
                            uMinY = min(uMinY, b[2]); uMaxY = max(uMaxY, b[3])
                        }
                    }
                }
                // Fractions of the hero canvas.
                val fx0 = uMinX / heroW; val fx1 = uMaxX / heroW
                val fy0 = uMinY / heroH; val fy1 = uMaxY / heroH
                val line = String.format(
                    "BOX %-11s %-30s zoom=%.4f  x=[%6.1f,%6.1f] (%.3f..%.3f W)  y=[%6.1f,%6.1f] (%.3f..%.3f H)",
                    category, name, frame.zoom, uMinX, uMaxX, fx0, fx1, uMinY, uMaxY, fy0, fy1
                )
                lines.append(line).append("\n")
                println(line)
                if (!first) json.append(",\n"); first = false
                json.append(String.format(
                    "      \"%s\": {\"zoom\": %.4f, \"x\": [%.3f, %.3f], \"y\": [%.3f, %.3f]}",
                    name, frame.zoom, fx0, fx1, fy0, fy1
                ))
                assertTrue("$name: rig left the hero horizontally ($fx0..$fx1)", fx0 >= -0.01f && fx1 <= 1.01f)
                assertTrue("$name: rig left the hero vertically ($fy0..$fy1)", fy0 >= -0.01f && fy1 <= 1.01f)
            }
            json.append("\n    }")
        }
        json.append("\n  },\n")

        // Overlay geometry for the report, at the three font scales.
        json.append("  \"overlay\": {\n")
        var firstFs = true
        for (fs in listOf(1.0f, 1.3f, 1.5f)) {
            if (!firstFs) json.append(",\n"); firstFs = false
            val windowH = 760f
            val heroDp = HeroGeometry.sessionHeroHeightDp(windowH, 44f * fs, 132f * fs, fs)
            val zones = HeroGeometry.overlayZones(heroDp, 64f * fs, 56f * fs)
            val band = HeroGeometry.safeBand(heroDp, zones)
            json.append(String.format(
                "    \"font_%.1f\": {\"hero_dp\": %.1f, \"share\": %.3f, \"top_overlay_dp\": %.1f, \"bottom_overlay_dp\": %.1f, \"safe_band_dp\": [%.1f, %.1f], \"safe_band_height_dp\": %.1f}",
                fs, heroDp, HeroGeometry.heroShare(heroDp, windowH), zones.topDp, zones.bottomDp, band.top, band.bottom, band.height
            ))
        }
        json.append("\n  }\n}\n")

        // Write to the module's build dir (inside the module root, reachable from user.dir).
        val start = File(System.getProperty("user.dir"))
        var dir = start
        for (i in 0 until 8) { if (File(dir, "app/build").isDirectory) break; dir = dir.parentFile ?: break }
        val out = File(dir, "app/build/geometry-report.json")
        out.parentFile.mkdirs()
        out.writeText(json.toString())
        println("GEOMETRY_REPORT " + out.absolutePath)
        println(lines.toString())
    }
}
