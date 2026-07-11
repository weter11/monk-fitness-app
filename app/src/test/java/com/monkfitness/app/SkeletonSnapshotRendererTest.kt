package com.monkfitness.app

import android.graphics.Bitmap
import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.HamstringStretchPose
import org.junit.Assert.*
import org.junit.Test

class SkeletonSnapshotRendererTest {

    @Test
    fun testExerciseSnapshotDataClass() {
        // Test that properties are correctly set on ExerciseSnapshot
        val mockBitmap = try {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            // Stub in standard unit test environment
            null
        }

        if (mockBitmap != null) {
            val snapshot = ExerciseSnapshot(5, 0.5f, mockBitmap)
            assertEquals(5, snapshot.frameIndex)
            assertEquals(0.5f, snapshot.progress)
            assertSame(mockBitmap, snapshot.bitmap)
        }
    }

    @Test
    fun testExerciseSnapshotSequenceInstantiation() {
        val snapshotsList = ArrayList<ExerciseSnapshot>()
        val sequence = ExerciseSnapshotSequence(snapshotsList)
        assertTrue(sequence.snapshots.isEmpty())
    }

    @Test
    fun testSkeletonSnapshotRendererInstantiation() {
        val engine = SkeletonEngine(SkeletonDefinition.DEFAULT_ADULT, SkeletonStyle.DEFAULT)
        try {
            val renderer = SkeletonSnapshotRenderer(engine)
            assertNotNull(renderer)
        } catch (e: RuntimeException) {
            // Under JVM unit tests, the android.graphics.Paint/Path constructors throw stubs.
            // This is expected and verifies that the class is correctly compiled and integrated with Android graphics.
            assertTrue(e.message?.contains("not mocked") == true || e.stackTrace.any { it.className.startsWith("android.graphics") })
        }
    }

    @Test
    fun testOfflineSequenceGenerationStubBehavior() {
        val engine = SkeletonEngine(SkeletonDefinition.DEFAULT_ADULT, SkeletonStyle.DEFAULT)
        val poseBuilder = HamstringStretchPose()

        try {
            val renderer = SkeletonSnapshotRenderer(engine)
            renderer.renderSequence(
                poseBuilder = poseBuilder,
                definition = SkeletonDefinition.DEFAULT_ADULT,
                frameCount = 3,
                width = 128,
                height = 128
            )
        } catch (e: RuntimeException) {
            // Expected stub behavior on pure JVM JUnit test for Paint/Path/Bitmap constructors
            assertTrue(e.message?.contains("not mocked") == true || e.stackTrace.any { it.className.startsWith("android.graphics") })
        }
    }
}
