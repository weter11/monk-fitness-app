package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.IsometricSidePlankPose
import com.monkfitness.app.poses.StaticForearmPlankPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **B-2 — one support declaration channel, and it reaches the published frame.**
 *
 * Contract: [`PoseMetadata.support`] (`SupportDefinition.pivot` + `.contacts`) is the SINGLE support
 * declaration channel. The engine derives `SkeletonPose.supportedPoints` from it on every production
 * path (`SkeletonPipeline.buildAndInject` for playback, `ExerciseAnimation` for the renderer path),
 * so the frame the engine PUBLISHES carries exactly the declared support model — and that published
 * carrier is what the Finalizer's support-plane derivation and every validator observe.
 *
 * ## What this guards (RED evidence on the pre-fix tree)
 *
 * `PoseMetadata` used to carry two further, write-only copies of the same fact
 * (`supportContacts: Set<SupportContact>`, `pivotType: PivotType`): 15 poses wrote them, nothing in
 * `app/src/main` ever read them (both the pipeline and the UI read `support.contacts`).
 * `StaticForearmPlankPose` and `IsometricSidePlankPose` declared their support ONLY on that dead
 * channel, so the published frame's `supportedPoints` was **empty** for both poses and the
 * Finalizer's support-plane derivation was entirely off (measured pre-fix on `origin/main` `dc4cc27`:
 * `plank_standard` / `side_plank_standard` publish `supportedPoints = []` at every progress value).
 *
 * ## Test shape
 *
 * The assertions are taken on the **published frame** of the production pipeline — not on a helper —
 * because the defect was precisely "the declaration is correct but production never consumes it".
 * The sensitivity guard ([removingTheDeclarationEmptiesThePublishedSupportModel]) proves the
 * assertions are driven by the declaration: remove it and the same observation collapses to the
 * empty model, i.e. the production assertions above go RED.
 */
class SupportDeclarationChannelTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** The declared support model, as the engine's own derivation reads it. */
    private fun declaredPoints(builder: PoseBuilder): Set<SupportPoint> =
        builder.metadata.support.contacts.map { it.point }.toSet()

    /** The support model the pipeline PUBLISHES for [builder] at [progress]. */
    private fun publishedPoints(builder: PoseBuilder, progress: Float): Set<SupportPoint> =
        SkeletonPipeline(def).produceFrame(builder, context(progress)).pose.supportedPoints.toSet()

    // ------------------------------------------------------------------
    // 1. Declaration -> canonical runtime representation, corpus-wide.
    // ------------------------------------------------------------------

    @Test
    fun everyProductionPosePublishesExactlyItsDeclaredSupportModel() {
        val corpus = productionPoseClasses()
        assertTrue(
            "anti-vacuity: the production pose corpus must be enumerated (found ${corpus.size})",
            corpus.size >= 45
        )

        val mismatches = mutableListOf<String>()
        val declaring = mutableListOf<String>()
        for (name in corpus) {
            val builder = MotionProbe.build(name)
            val declared = declaredPoints(builder)
            if (declared.isEmpty()) continue
            declaring.add(name)
            for (p in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                val published = publishedPoints(builder, p)
                if (published != declared) {
                    mismatches.add("$name p=$p declared=$declared published=$published")
                }
            }
        }

        assertTrue(
            "every pose that declares support must publish exactly that model:\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty()
        )
        assertTrue(
            "anti-vacuity: the corpus must contain declaring poses (found $declaring)",
            declaring.size >= 10
        )
        // B-2's two victims are the reason this test exists — they must be in the declaring set,
        // i.e. their declaration must reach the published frame (pre-fix both published an EMPTY set).
        assertTrue(
            "the two poses whose declaration lived on the removed channel must declare AND publish: $declaring",
            declaring.containsAll(listOf("StaticForearmPlankPose", "IsometricSidePlankPose"))
        )
    }

    // ------------------------------------------------------------------
    // 2 + 3. The two B-2 poses no longer lose their declared support.
    // ------------------------------------------------------------------

    @Test
    fun forearmPlankPublishesItsDeclaredSupportModel() {
        assertDeclaredSupportIsPublished(
            StaticForearmPlankPose(),
            setOf(
                SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM,
                SupportPoint.LEFT_TOES, SupportPoint.RIGHT_TOES
            )
        )
    }

    @Test
    fun sidePlankPublishesItsDeclaredSupportModel() {
        assertDeclaredSupportIsPublished(
            IsometricSidePlankPose(),
            setOf(SupportPoint.RIGHT_FOREARM, SupportPoint.RIGHT_FOOT)
        )
    }

    private fun assertDeclaredSupportIsPublished(builder: PoseBuilder, expected: Set<SupportPoint>) {
        // The declaration itself must be on the canonical channel (pre-fix it was on the dead one,
        // so this assertion alone is RED on the pre-fix tree for both planks).
        assertEquals("declared support model", expected, declaredPoints(builder))
        for (p in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            assertEquals(
                "published support model at p=$p must be the declared one",
                expected, publishedPoints(builder, p)
            )
        }
        // Cold first frame: the same carrier is stamped by the same injection site.
        val cold = SkeletonPipeline(def).produceFrame(builder, context(0.5f)).pose
        assertEquals("cold-frame published support model", expected, cold.supportedPoints.toSet())
    }

    // ------------------------------------------------------------------
    // 4. Sensitivity: break the declaration and the observation must collapse.
    // ------------------------------------------------------------------

    /**
     * The forearm plank with its declaration deliberately removed. It delegates `build()` to the
     * production pose and varies ONLY the declaration, so the observation below isolates exactly
     * what B-2 was about.
     */
    private class DeclaredSupportRemovedPlank : PoseBuilder by StaticForearmPlankPose() {
        override val metadata = StaticForearmPlankPose().metadata.copy(
            support = SupportDefinition(pivot = PivotType.ELBOWS, contacts = emptySet())
        )
    }

    @Test
    fun removingTheDeclarationEmptiesThePublishedSupportModel() {
        val broken = DeclaredSupportRemovedPlank()
        assertEquals("the twin declares no support", emptySet<SupportPoint>(), declaredPoints(broken))
        for (p in listOf(0.0f, 0.5f, 1.0f)) {
            assertEquals(
                "with no declaration the published model must be empty at p=$p",
                emptySet<SupportPoint>(), publishedPoints(broken, p)
            )
        }
        // …which is exactly what the production pose no longer does: the two observations differ, so
        // the assertions in this class are driven by the production declaration and are not vacuous.
        val production = StaticForearmPlankPose()
        assertTrue(
            "the declared model must be observable on the published frame (this is what fails when " +
                "a pose's declaration is dropped or routed to a channel nothing reads)",
            publishedPoints(production, 0.5f).isNotEmpty()
        )
    }

    // ------------------------------------------------------------------
    // Corpus enumeration (mirrors RootAuthorityTest's walk-up idiom).
    // ------------------------------------------------------------------

    /** Concrete production pose classes in `poses/` (base classes and the registry excluded). */
    private fun productionPoseClasses(): List<String> {
        var dir = File(System.getProperty("user.dir"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot
            ?: error("Could not locate app module root from ${System.getProperty("user.dir")}")
        val srcDir = File(root, "src/main/java/com/monkfitness/app/poses")
        return srcDir.listFiles { f -> f.isFile && f.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" }
            .sorted()
    }
}
