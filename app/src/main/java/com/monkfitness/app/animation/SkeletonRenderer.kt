package com.monkfitness.app.animation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp

@Composable
fun SkeletonRenderer(
    pose: SkeletonPose,
    camera: Camera,
    engine: SkeletonEngine,
    modifier: Modifier = Modifier,
    showGround: Boolean = true,
    highlightedJoint: Joint? = null,
    screenSpaceSettings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    val style = engine.style
    val finalizer = remember(engine.definition) { SkeletonPoseFinalizer(engine.definition) }
    val projector = remember { SkeletonProjector() }
    val compensator = remember(screenSpaceSettings) { ScreenSpaceCompensation(screenSpaceSettings) }

    val skeletonBuffer = remember { ProjectedSkeleton() }
    val renderItems = remember { Array(100) { RenderItem() } }
    val indices = remember { IntArray(100) }
    val scaleBuffer = remember { ScreenSpaceScale() }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val finalizedPose = finalizer.finalize(pose)
        performValidationPass(finalizedPose, engine)
        projector.project(finalizedPose, camera, engine, width, height, skeletonBuffer)

        // Bulk compute ScreenSpaceScale values for all joints using contiguous indexed arrays
        compensator.computeScales(skeletonBuffer.joints, skeletonBuffer.jointScales)

        if (showGround) {
            drawGroundPassive(skeletonBuffer, style, camera.zoom)
        }

        var itemCount = 0

        for (i in 0 until skeletonBuffer.boneCount) {
            val b = skeletonBuffer.bones[i]
            compensator.computeScale(b.p1, scaleBuffer)
            val thickness = b.thickness * scaleBuffer.thicknessScale * camera.zoom
            renderItems[itemCount++].populateBone(b, thickness, scaleBuffer.outlineScale)
        }

        for (j in skeletonBuffer.indicators) {
            val scale = skeletonBuffer.jointScales[j.id.index]
            val radius = (if (j.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) *
                        scale.radiusScale * camera.zoom
            renderItems[itemCount++].populateJoint(j, radius, scale.outlineScale)
        }

        for (i in 0 until skeletonBuffer.faceCount) {
            renderItems[itemCount++].populateFace(skeletonBuffer.faces[i])
        }

        // Initialize indices
        for (i in 0 until itemCount) indices[i] = i

        // In-place Insertion Sort of indices based on renderItems[idx].depth
        for (i in 1 until itemCount) {
            val idx = indices[i]
            val depth = renderItems[idx].depth
            var j = i - 1
            while (j >= 0 && renderItems[indices[j]].depth < depth) {
                indices[j + 1] = indices[j]
                j--
            }
            indices[j + 1] = idx
        }

        if (highlightedJoint != null) {
            val p = skeletonBuffer.joints[highlightedJoint.index]
            drawCircle(Color.White.copy(alpha = 0.5f), 12f * p.perspectiveScale * camera.zoom, Offset(p.x, p.y))
        }

        for (i in 0 until itemCount) {
            val item = renderItems[indices[i]]
            when (item.type) {
                RenderItem.Type.BONE -> {
                    val b = item.bone!!
                    val dx = b.p1.x - b.p2.x
                    val dy = b.p1.y - b.p2.y
                    val isIdentical = (dx * dx + dy * dy) < 0.01f
                    val isInvalid = b.p1.x.isNaN() || b.p1.y.isNaN() || b.p2.x.isNaN() || b.p2.y.isNaN() ||
                                    b.p1.x.isInfinite() || b.p1.y.isInfinite() || b.p2.x.isInfinite() || b.p2.y.isInfinite() ||
                                    (b.p1.x == 0f && b.p1.y == 0f && b.p2.x == 0f && b.p2.y == 0f)

                    if (!isIdentical && !isInvalid) {
                        val color = getZColor(item.depth, b.isForeground, style)
                        val outline = style.outlineWidth * item.outlineScale
                        drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness + (outline * 2f), Color(0xFF0A0F14))
                        drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness, color)
                    }
                }
                RenderItem.Type.JOINT -> {
                    val j = item.joint!!
                    val color = getZColor(item.depth, j.isIndicator, style)
                    val outline = 2f * item.outlineScale
                    drawCircle(Color(0xFF0A0F14), item.radius + outline, Offset(j.point.x, j.point.y))
                    drawCircle(color, item.radius, Offset(j.point.x, j.point.y))
                }
                RenderItem.Type.FACE -> {
                    val f = item.face!!
                    val color = getZColor(f.avgDepth, false, style)
                    val strokeC = Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, 1.0f)
                    val fillC = Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, 1.0f)
                    path.reset()
                    val facePoints = f.points
                    for (idx in 0 until facePoints.size) {
                        val p = facePoints[idx]
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, fillC)
                    drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = style.outlineWidth))
                }
                else -> {}
            }
        }
    }
}

private class RenderItem {
    enum class Type { NONE, BONE, JOINT, FACE }
    var type = Type.NONE
    var depth = 0f

    var bone: ProjectedBone? = null
    var thickness = 0f
    var outlineScale = 0f

    var joint: ProjectedJoint? = null
    var radius = 0f

    var face: ProjectedFace? = null

    fun populateBone(b: ProjectedBone, thick: Float, outline: Float) {
        type = Type.BONE
        bone = b
        depth = b.avgDepth
        thickness = thick
        outlineScale = outline
    }

    fun populateJoint(j: ProjectedJoint, rad: Float, outline: Float) {
        type = Type.JOINT
        joint = j
        depth = j.point.depth
        radius = rad
        outlineScale = outline
    }

    fun populateFace(f: ProjectedFace) {
        type = Type.FACE
        face = f
        depth = f.avgDepth
    }
}

private fun getZColor(depth: Float, isForeground: Boolean, style: SkeletonStyle): Color {
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val baseC = lerp(style.farColor, style.secondaryColor, t)
    return if (isForeground) lerp(baseC, style.primaryColor, 0.3f) else baseC
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearBone(start: Offset, end: Offset, thickness: Float, color: Color) {
    drawLine(color = color, start = start, end = end, strokeWidth = thickness, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroundPassive(skeleton: ProjectedSkeleton, style: SkeletonStyle, zoom: Float) {
    val gridColor = Color(0x5A3A445C)
    for (i in 0 until skeleton.gridLineCount) {
        val l = skeleton.gridLines[i]
        drawLine(gridColor, Offset(l.p1.x, l.p1.y), Offset(l.p2.x, l.p2.y), strokeWidth = 1f)
    }
    val shadowColor = Color(0x9605080C)
    val shadowPoints = skeleton.shadowPoints
    for (i in 0 until shadowPoints.size) {
        val p = shadowPoints[i]
        val sx = style.shadowRadiusX * p.perspectiveScale * zoom
        val sy = style.shadowRadiusY * p.perspectiveScale * zoom
        drawOval(shadowColor, Offset(p.x - sx, p.y - sy), androidx.compose.ui.geometry.Size(sx * 2f, sy * 2f))
    }
}

private fun performValidationPass(pose: SkeletonPose, engine: SkeletonEngine) {
    val def = engine.definition
    val joints = pose.joints

    // 1. Duplicate joint indices
    val indicesList = Joint.entries.map { it.index }
    val uniqueIndices = indicesList.distinct()
    if (indicesList.size != uniqueIndices.size) {
        val duplicates = indicesList.groupBy { it }.filter { it.value.size > 1 }.keys
        println("[VALIDATION ALERT] Duplicate joint indices found: $duplicates")
    }

    // 2. Missing joints
    val hasNonZero = joints.any { it.x != 0f || it.y != 0f || it.z != 0f }
    if (hasNonZero) {
        for (joint in Joint.entries) {
            val pos = joints[joint.index]
            if (pos.x == 0f && pos.y == 0f && pos.z == 0f && joint != Joint.PELVIS) {
                println("[VALIDATION ALERT] Missing joint: ${joint.name} is at (0, 0, 0)")
            }
        }
    }

    // 3. Bone validation
    for (bone in engine.bones) {
        val p = joints[bone.parentJoint.index]
        val c = joints[bone.childJoint.index]

        // Invalid parent-child connection
        if (bone.parentJoint == bone.childJoint) {
            println("[VALIDATION ALERT] Invalid parent-child connection: Bone connects ${bone.parentJoint.name} to itself!")
            continue
        }
        if (bone.parentJoint.index !in joints.indices || bone.childJoint.index !in joints.indices) {
            println("[VALIDATION ALERT] Invalid parent-child connection: Bone joint indices out of bounds!")
            continue
        }

        // Distance in 3D
        val dx = p.x - c.x
        val dy = p.y - c.y
        val dz = p.z - c.z
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

        // Zero-length bones
        if (length < 0.1f) {
            println("[VALIDATION ALERT] Zero-length bone found between ${bone.parentJoint.name} and ${bone.childJoint.name} (length: $length)")
        }

        // Expected length comparison (Anatomical reference)
        val refLength = when {
            (bone.parentJoint == Joint.HIP_B && bone.childJoint == Joint.KNEE_B) ||
            (bone.parentJoint == Joint.HIP_F && bone.childJoint == Joint.KNEE_F) -> def.thighLength

            (bone.parentJoint == Joint.KNEE_B && bone.childJoint == Joint.ANKLE_B) ||
            (bone.parentJoint == Joint.KNEE_F && bone.childJoint == Joint.ANKLE_F) -> def.shinLength

            (bone.parentJoint == Joint.ANKLE_B && bone.childJoint == Joint.HEEL_B) ||
            (bone.parentJoint == Joint.ANKLE_F && bone.childJoint == Joint.HEEL_F) -> def.footLength * def.foot.heelRatio

            (bone.parentJoint == Joint.ANKLE_B && bone.childJoint == Joint.TOE_B) ||
            (bone.parentJoint == Joint.ANKLE_F && bone.childJoint == Joint.TOE_F) -> def.footLength * def.foot.toeRatio

            (bone.parentJoint == Joint.HEEL_B && bone.childJoint == Joint.TOE_B) ||
            (bone.parentJoint == Joint.HEEL_F && bone.childJoint == Joint.TOE_F) -> def.footLength

            (bone.parentJoint == Joint.SHOULDER_P && bone.childJoint == Joint.ELBOW_P) ||
            (bone.parentJoint == Joint.SHOULDER_A && bone.childJoint == Joint.ELBOW_A) -> def.upperArmLength

            (bone.parentJoint == Joint.ELBOW_P && bone.childJoint == Joint.WRIST_P) ||
            (bone.parentJoint == Joint.ELBOW_A && bone.childJoint == Joint.WRIST_A) -> def.forearmLength

            (bone.parentJoint == Joint.WRIST_P && bone.childJoint == Joint.PALM_P) ||
            (bone.parentJoint == Joint.WRIST_A && bone.childJoint == Joint.PALM_A) -> def.hand.palmLength

            (bone.parentJoint == Joint.PALM_P && bone.childJoint == Joint.FINGERTIPS_P) ||
            (bone.parentJoint == Joint.PALM_A && bone.childJoint == Joint.FINGERTIPS_A) -> def.hand.palmLength + def.hand.fingerLength

            (bone.parentJoint == Joint.PELVIS && bone.childJoint == Joint.CHEST) -> def.torsoLength
            (bone.parentJoint == Joint.CHEST && bone.childJoint == Joint.NECK_END) -> def.neckLength
            else -> null
        }

        if (refLength != null) {
            val ratio = length / refLength
            if (ratio > 2.0f) {
                println("[VALIDATION ALERT] Unexpected long bone between ${bone.parentJoint.name} and ${bone.childJoint.name}: length is $length (ref: $refLength, ratio: $ratio)")
            }
        }
    }
}
