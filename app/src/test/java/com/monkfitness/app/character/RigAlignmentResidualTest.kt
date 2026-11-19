package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * A2: the measured residual. Each mapped bone head is reconstructed from its skinning matrix and
 * compared with the engine joint that should sit on it, on five named frames drawn from four
 * registered builders — so the row decides whether the pair (rig, retarget) holds the joints in
 * motion, which a bind-stance check cannot see.
 */
class RigAlignmentResidualTest {

    private val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre: Float = X1Bind.unitsPerMetre(definition)
    private val rig: GltfRig = RetargetFixtures.assetRig()
    private val boneByJoint: Map<Joint, Int> = RetargetFixtures.boneByJoint(rig)
    private val bind: Map<Joint, Vector3> = X1Bind.bindJoints(definition)

    private class Frame(val builderId: String, val label: String, val progress: Float) {
        val id: String get() = "$builderId/$label"
    }

    private class Row(val joint: Joint, val frame: Frame, val units: Float, val millimetres: Float)

    // Five frames, four builders: squat bottom and squat top are two frames of one builder, so the
    // pose count and the builder count are different numbers and are reported separately.
    private companion object {
        val SQUAT_BOTTOM = Frame("squat_standard", "bottom", 1.0f)
        val SQUAT_TOP = Frame("squat_standard", "top", 0.0f)
        // The push-up's depth curve is `0.5 - 0.5·cos(2πp)`, so the bottom is at half progress.
        val PUSHUP_BOTTOM = Frame("pushup_standard", "bottom", 0.5f)
        val BIRDDOG = Frame("birddog_hold", "hold", 1.0f)
        val ROW_MID = Frame("row_standard", "mid-pull", 0.5f)
        val FRAMES = listOf(SQUAT_BOTTOM, SQUAT_TOP, PUSHUP_BOTTOM, BIRDDOG, ROW_MID)

        // Not one of the five frames: the label the bind-frame row carries.
        val BIND = Frame("x1_bind", "bind", 0f)
    }

    private val poses: Map<Frame, SkeletonPose> by lazy {
        FRAMES.associateWith { RetargetFixtures.publishedPose(it.builderId, it.progress, definition) }
    }

    private fun rows(retargeter: PoseRetargeter, frame: Frame, pose: SkeletonPose): List<Row> {
        val matrices = retargeter.boneMatrices(pose, unitsPerMetre)
        return BoneMap.byJoint.keys.map { joint ->
            val head = RetargetFixtures.headEngine(matrices, rig, joint, boneByJoint, definition)
            val engine = pose.getJoint(joint)
            val dx = head.x - engine.x
            val dy = head.y - engine.y
            val dz = head.z - engine.z
            val units = sqrt(dx * dx + dy * dy + dz * dz)
            Row(joint, frame, units, units * 1000f / unitsPerMetre)
        }
    }

    private fun millimetres(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz) * 1000f / unitsPerMetre
    }

    @Test
    fun theResidualIsWithinToleranceAtAllFiveFrames() {
        val retargeter = RetargetFixtures.retargeter(rig)
        val measured = FRAMES.flatMap { frame -> rows(retargeter, frame, poses.getValue(frame)) }
        val worst = measured.maxByOrNull { it.millimetres }!!

        assertEquals("A2 covers 23 mapped joints on 5 frames", 115, measured.size)
        assertEquals("five distinct frames", 5, measured.map { it.frame.id }.distinct().size)
        assertEquals("four distinct builders", 4, measured.map { it.frame.builderId }.distinct().size)

        println("A2 residual — rig bone head vs engine joint, engine units ↦ mm")
        println(String.format("%-12s %-28s %10s %10s", "joint", "frame", "u", "mm"))
        measured.sortedByDescending { it.millimetres }.forEach { row ->
            println(
                String.format(
                    "%-12s %-28s %10.6f %10.6f",
                    row.joint, row.frame.id, row.units, row.millimetres
                )
            )
        }
        val summary = String.format(
            "frames=5 builders=4 worst=%s@%s %.4fmm",
            worst.joint, worst.frame.id, worst.millimetres
        )
        println(summary)

        val over = measured.filter { it.units > X1Bind.TAU_RESIDUAL || it.millimetres > X1Bind.TAU_RESIDUAL_MM }
        assertTrue(
            "A2: ${over.size}/${measured.size} rows over ${X1Bind.TAU_RESIDUAL_MM} mm — " +
                over.joinToString { "${it.joint}@${it.frame.id} ${it.millimetres}mm" } +
                "; worst ${worst.joint}@${worst.frame.id} ${worst.millimetres}mm " +
                "(${worst.units}u > ${X1Bind.TAU_RESIDUAL}u)",
            over.isEmpty()
        )
    }

    @Test
    fun theFiveFramesArePosedAndNotTheBindStance() {
        FRAMES.forEach { frame ->
            val pose = poses.getValue(frame)
            val displaced = BoneMap.byJoint.keys.associateWith {
                millimetres(pose.getJoint(it), bind.getValue(it))
            }
            val moved = displaced.count { it.value > 10f }
            val worst = displaced.maxByOrNull { it.value }!!
            println(
                String.format(
                    "%-28s moved>10mm=%2d of %2d  worst=%s %.3fmm",
                    frame.id, moved, BoneMap.byJoint.size, worst.key, worst.value
                )
            )
            // The standing squat returns the trunk and hips to the X1 pose by construction, so its
            // ten moved heads are only the elbow/hand and knee/ankle/toe rows; ten is the floor.
            assertTrue(
                "${frame.id}: only $moved of ${BoneMap.byJoint.size} heads moved > 10 mm from bind " +
                    "(${displaced.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }})",
                moved >= 10
            )
            assertTrue("${frame.id}: the frame must be posed, worst displacement was ${worst.value} mm", worst.value > 50f)
        }

        assertTrue(
            "squat bottom must publish a nonzero pelvis rotation",
            poses.getValue(SQUAT_BOTTOM).getJointRotation(Joint.PELVIS).angle != 0f
        )
        assertTrue(
            "bird-dog must publish a nonzero pelvis rotation",
            poses.getValue(BIRDDOG).getJointRotation(Joint.PELVIS).angle != 0f
        )
    }

    @Test
    fun theBindFrameMeasuresZero() {
        val pose = RetargetFixtures.x1Pose()
        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, unitsPerMetre)

        val measured = BoneMap.byJoint.keys.map { joint ->
            val head = RetargetFixtures.headEngine(matrices, rig, joint, boneByJoint, definition)
            val engine = pose.getJoint(joint)
            val dx = head.x - engine.x
            val dy = head.y - engine.y
            val dz = head.z - engine.z
            val units = sqrt(dx * dx + dy * dy + dz * dz)
            Row(joint, BIND, units, units * 1000f / unitsPerMetre)
        }
        val worst = measured.maxByOrNull { it.millimetres }!!
        assertTrue(
            "at bind every mapped head is its joint: worst ${worst.joint} at ${worst.millimetres} mm",
            measured.all { it.millimetres < 1e-3f }
        )

        // The reconstruction itself, on every skin joint: M · [v_rest, 1] is the rest head. Reading
        // the translation column instead gives 0 for all of them and would call the rig perfect.
        val rest = rig.restWorldTranslations()
        var deviation = 0f
        var at = ""
        for (k in rig.jointNodes.indices) {
            val node = rig.jointNodes[k]
            val base = k * 16
            val x = matrices[base] * rest[node][0] + matrices[base + 4] * rest[node][1] +
                matrices[base + 8] * rest[node][2] + matrices[base + 12]
            val y = matrices[base + 1] * rest[node][0] + matrices[base + 5] * rest[node][1] +
                matrices[base + 9] * rest[node][2] + matrices[base + 13]
            val z = matrices[base + 2] * rest[node][0] + matrices[base + 6] * rest[node][1] +
                matrices[base + 10] * rest[node][2] + matrices[base + 14]
            val dx = x - rest[node][0]
            val dy = y - rest[node][1]
            val dz = z - rest[node][2]
            val error = sqrt(dx * dx + dy * dy + dz * dz)
            if (error > deviation) {
                deviation = error
                at = rig.nodes[node].name
            }
        }
        assertEquals("A2 covers every skin joint's head, not only the mapped ones", 34, rig.jointNodes.size)
        assertTrue("M · [v_rest, 1] must reproduce the rest head at $at (worst $deviation m)", deviation < 1e-5f)
    }

    @Test
    fun equalLengthDifferentDirectionIsANonZeroResidual() {
        val skinned = RetargetFixtures.chainRig(
            names = listOf("Hips", "Spine"),
            parents = listOf(-1, 0),
            translations = listOf(
                X1Bind.toAsset(bind.getValue(Joint.PELVIS), unitsPerMetre),
                Vector3(0.100f, 0f, 0f)
            )
        )
        // chainRig authors identity inverse binds; a skinning matrix reconstructs a head only when
        // they invert the rest world, which is what every parsed rig carries.
        val restWorld = skinned.restWorldTransforms()
        val chain = GltfRig(
            nodes = skinned.nodes,
            sceneRoots = skinned.sceneRoots,
            jointNodes = skinned.jointNodes,
            skinnedNodes = skinned.skinnedNodes,
            inverseBinds = restWorld.map { Mat4.inverse(it) },
            triangleCount = skinned.triangleCount,
            morphTargetCount = skinned.morphTargetCount,
            animationNames = skinned.animationNames
        )
        val bones = mapOf(Joint.PELVIS to 0, Joint.LUMBAR to 1)
        val pelvis = bind.getValue(Joint.PELVIS)
        val restOffset = Vector3(0.100f, 0f, 0f)
        // The same 100 mm segment, turned 0.1° off the parent's axis: identical lengths, so the
        // withdrawn bound reports no error at all while the head has moved.
        val publishedOffset = RetargetFixtures.rotate(
            RetargetFixtures.axisAngle(Vector3(0f, 1f, 0f), 0.1f),
            X1Bind.toEngine(restOffset, unitsPerMetre)
        )
        val publishedLumbar = Vector3(
            pelvis.x + publishedOffset.x,
            pelvis.y + publishedOffset.y,
            pelvis.z + publishedOffset.z
        )
        val pose = RetargetFixtures.pose(mapOf(Joint.LUMBAR to publishedLumbar))

        val retargeter = PoseRetargeter(chain, bones, BoneMap.basis, definition)
        val matrices = retargeter.boneMatrices(pose, unitsPerMetre)
        val head = RetargetFixtures.headEngine(matrices, chain, Joint.LUMBAR, bones, definition)
        val residualMm = millimetres(head, publishedLumbar)

        val rigLengthMm = sqrt(
            restOffset.x * restOffset.x + restOffset.y * restOffset.y + restOffset.z * restOffset.z
        ) * 1000f
        val engineLengthMm = sqrt(
            publishedOffset.x * publishedOffset.x + publishedOffset.y * publishedOffset.y +
                publishedOffset.z * publishedOffset.z
        ) * 1000f / unitsPerMetre
        val lengthSumMm = kotlin.math.abs(rigLengthMm - engineLengthMm)

        assertEquals("the withdrawn bound sees no length error at all", 0f, lengthSumMm, 1e-4f)
        assertEquals(
            "an equal-length 0.1° direction error still displaces the head by 0.1745 mm",
            0.1745f,
            residualMm,
            0.005f
        )
    }

    @Test
    fun droppingTheBasisMovesEveryHeadByMetres() {
        val pose = poses.getValue(BIRDDOG)
        val dropped = PoseRetargeter(rig, boneByJoint, Quat.IDENTITY, definition)
        val measured = rows(dropped, BIRDDOG, pose)
        val worst = measured.maxByOrNull { it.units }!!

        println(
            "dropped basis, worst six: " + measured.sortedByDescending { it.units }
                .take(6)
                .joinToString { "${it.joint}=${it.units / unitsPerMetre}m" }
        )
        assertTrue(
            "an identity basis must move the measured frame: worst was ${worst.joint} at " +
                "${worst.units / unitsPerMetre} m",
            worst.units / unitsPerMetre > 0.5f
        )
        listOf(Joint.PELVIS, Joint.LUMBAR).forEach { joint ->
            val row = measured.first { it.joint == joint }
            val metres = row.units / unitsPerMetre
            // The root carries a translation, not a segment direction: a dropped basis mis-rotates
            // only that displacement, so it stays well under the metres the limbs pick up.
            assertTrue(
                "$joint rides the root and carries no segment direction: $metres m",
                metres < 0.25f
            )
            assertTrue(
                "$joint must stay far below the worst limb residual (${worst.units / unitsPerMetre} m): $metres m",
                metres < worst.units / unitsPerMetre / 4f
            )
        }
    }

    @Test
    fun theResidualTableIsReproducible() {
        fun pass(): List<Row> = FRAMES.flatMap { frame ->
            val pose = RetargetFixtures.publishedPose(frame.builderId, frame.progress, definition)
            rows(RetargetFixtures.retargeter(rig), frame, pose)
        }

        val first = pass()
        val second = pass()
        assertEquals("both passes cover the same rows", first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals("${a.joint}@${a.frame.id} must reproduce", a.joint, b.joint)
            assertEquals("${a.joint}@${a.frame.id} must reproduce", a.frame.id, b.frame.id)
            assertEquals(
                "${a.joint}@${a.frame.id} drifted between passes",
                a.millimetres.toDouble(),
                b.millimetres.toDouble(),
                1e-6
            )
        }
    }

    @Test
    fun theMappedJointCountIsTheMapNotTheSkin() {
        assertEquals("23 engine joints carry a bone", 23, BoneMap.byJoint.size)
        assertEquals("10 engine joints are declared undrivable", 10, BoneMap.UNMAPPED.size)
        assertEquals("11 bones are visual-only", 11, BoneMap.VISUAL_ONLY.size)
        assertEquals("the asset's skin carries 34 joints", 34, rig.jointNodes.size)
        assertEquals(
            "every mapped joint resolves on this rig, so no row is silently missing",
            BoneMap.byJoint.size,
            boneByJoint.size
        )
        assertTrue(
            "every resolved bone is a skin joint",
            rig.jointNodes.toSet().containsAll(boneByJoint.values)
        )

        val mappedNames = BoneMap.byJoint.values.mapTo(HashSet()) { GltfRig.normalizeName(it) }
        BoneMap.VISUAL_ONLY.forEach { bone ->
            assertTrue(
                "$bone is visual-only and must not carry a residual row",
                GltfRig.normalizeName(bone) !in mappedNames
            )
        }
        BoneMap.UNMAPPED.forEach { joint ->
            assertTrue("$joint has no bone and must not resolve", joint !in boneByJoint)
        }
    }
}
