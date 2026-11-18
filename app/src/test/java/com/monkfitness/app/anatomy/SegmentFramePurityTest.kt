package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Measuring a frame must not move it: the derivation reads the carrier and writes only the caller's
 * buffer, and it retains nothing that could outlive the call.
 */
class SegmentFramePurityTest {

    private val frames = SegmentFrames(AnatomyPoseFixture.DEFINITION)
    private val set = SegmentFrameSet()

    @Test
    fun derivingFramesLeavesTheInputPoseBitIdentical() {
        val pose = AnatomyPoseFixture.livePose("squat_standard", 0.42f)
        val before = snapshot(pose)
        frames.derive(pose, set)
        assertEquals(
            "the derivation mutated the pose it measured (joints, rotations and roots must be untouched)",
            before,
            snapshot(pose)
        )
    }

    @Test
    fun everyPublishedJointKeepsItsBitsAcrossAFullSweep() {
        for (pilot in AnatomyPoseFixture.PILOTS) {
            for (pose in AnatomyPoseFixture.swept(pilot)) {
                val before = snapshot(pose)
                frames.derive(pose, set)
                assertEquals("$pilot frame was mutated by its own measurement", before, snapshot(pose))
            }
        }
    }

    @Test
    fun theFramesDoNotDependOnTheCarrierRotations() {
        val pose = AnatomyPoseFixture.livePose("birddog_hold", 0.5f)
        frames.derive(pose, set)
        val fromRealRotations = axes(set)

        // The published rotations do not describe the segment (a solved limb's middle joint carries
        // its parent's rotation), so the derivation must read positions only.
        for (joint in Joint.entries) {
            pose.setJointRotation(joint, JointRotation(Vector3(0.3f, 0.5f, 0.8f), 1.234f))
        }
        frames.derive(pose, set)
        assertEquals("the frames moved when only the rotations changed", fromRealRotations, axes(set))
    }

    @Test
    fun theDerivationRetainsNoPoseOrCacheField() {
        val barred = listOf(SkeletonPose::class.java, SkeletonNode::class.java, Vector3::class.java, JointRotation::class.java)
        for (type in listOf(SegmentFrames::class.java, SegmentFrameSet::class.java)) {
            for (field in type.declaredFields) {
                if (field.isSynthetic) continue
                assertFalse(
                    "${type.simpleName}.${field.name} is a ${field.type.simpleName}: a retained pose or node would outlive the call",
                    barred.any { it.isAssignableFrom(field.type) }
                )
                if (field.type.isArray) {
                    assertFalse(
                        "${type.simpleName}.${field.name} is an array of ${field.type.componentType?.simpleName}",
                        barred.any { it.isAssignableFrom(field.type.componentType) }
                    )
                }
            }
        }
    }

    @Test
    fun clearingTheBufferLeavesNoFrameReadable() {
        frames.derive(AnatomyPoseFixture.bindPose(), set)
        set.clear()
        for (id in SegmentId.entries) {
            assertEquals("${id.name} was not cleared", SegmentFrameStatus.INVALID, set.status(id))
            assertEquals("${id.name} kept a reason", SegmentFrameReason.UNSET, set.reason(id))
            for (axis in 0..2) for (component in 0..2) {
                assertEquals("${id.name} kept an axis", 0f, set.axis(id, axis, component), 0f)
            }
        }
    }

    @Test
    fun theAnatomyPackageImportsNothingFromOutsideTheMotionCore() {
        val sources = anatomySources()
        assertTrue("the anatomy package has sources", sources.isNotEmpty())
        val forbidden = listOf(
            "android.",
            "androidx.",
            "com.monkfitness.app.ui.",
            "com.monkfitness.app.render.",
            "com.monkfitness.app.character.",
            "filament.",
            "com.google.android.filament."
        )
        val offenders = sources.flatMap { (path, lines) ->
            lines.filter { it.trim().startsWith("import ") }
                .filter { line -> forbidden.any { line.contains(it) } }
                .map { "$path: ${it.trim()}" }
        }
        assertEquals("the anatomy package must stay pure Kotlin: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun theAnatomyPackageDoesNotReinventTheEngineHelpersItIsForbiddenToUse() {
        val tokens = listOf(
            "getRotationToAlign" to "the degeneracy policy belongs to this package, not to the engine's align helper",
            "unitsPerMetre" to "the derivation is unit-free: no calibration constant enters a rotation",
            "metresPerUnit" to "the derivation is unit-free: no calibration constant enters a rotation",
            "anatomicalForward" to "a frame comes from published positions, never from a declared forward axis"
        )
        val offenders = anatomySources().flatMap { (path, lines) ->
            tokens.filter { (token, _) -> lines.any { it.contains(token) } }
                .map { (token, why) -> "$path contains $token ($why)" }
        }
        assertEquals("forbidden construction used in the anatomy package", emptyList<String>(), offenders)
    }

    @Test
    fun noProductionConsumerReadsTheSegmentFramesYet() {
        val consumers = listOf(
            "character/PoseRetargeter.kt",
            "character/CharacterSession.kt",
            "animation/SkeletonPipeline.kt",
            "animation/ValidationReport.kt"
        )
        val base = moduleRoot()
        val offenders = consumers.map { "$base/src/main/java/com/monkfitness/app/$it" }
            .filter { File(it).isFile }
            .filter { File(it).readText().contains("anatomy") }
        assertEquals(
            "segment frames are a stage-1 measurement, not a wired consumer: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    private fun snapshot(pose: SkeletonPose): String {
        val builder = StringBuilder()
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            builder.append(bits(p.x)).append(',').append(bits(p.y)).append(',').append(bits(p.z)).append(';')
            val r = pose.getJointRotation(joint)
            builder.append(bits(r.axis.x)).append(',').append(bits(r.axis.y)).append(',').append(bits(r.axis.z))
            builder.append(',').append(bits(r.angle)).append('|')
        }
        builder.append(pose.roots.size).append(':').append(pose.isTransformsUpdated)
        builder.append(':').append(bits(pose.rootRotationDelta)).append(':').append(pose.contacts.size)
        builder.append(':').append(pose.jointIntents.size).append(':').append(pose.maxIkClampAmount)
        return builder.toString()
    }

    private fun axes(set: SegmentFrameSet): String {
        val builder = StringBuilder()
        for (id in SegmentId.entries) for (axis in 0..2) for (component in 0..2) {
            builder.append(bits(set.axis(id, axis, component))).append(',')
        }
        return builder.toString()
    }

    private fun bits(value: Float): Int = java.lang.Float.floatToRawIntBits(value)

    private fun anatomySources(): List<Pair<String, List<String>>> {
        val dir = File(moduleRoot(), "src/main/java/com/monkfitness/app/anatomy")
        assertTrue("the anatomy source directory exists at ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.path to it.readLines() }
            .toList()
    }

    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("could not locate the app module root from ${System.getProperty("user.dir")}")
    }
}
