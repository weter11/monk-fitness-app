package com.monkfitness.app.anatomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped library and the checks that decide whether it is usable.
 *
 * The negative cases are built by copying the shipped definition rather than by writing a second
 * JSON fixture, so a rule cannot pass here on a hand-crafted object the asset could never produce.
 */
class AnatomyDefinitionTest {

    private val definition: AnatomyDefinition =
        AnatomyDefinitionLoader.load(File(ASSET).readText())

    @Test
    fun theShippedDefinitionPassesEveryMechanicalCheck() {
        val report = AnatomyDefinitionCheck.check(definition)
        assertTrue("the shipped definition is not clean: ${report.issues}", report.isClean)
    }

    @Test
    fun everyShippedAttachmentIsReportedFrameUnverifiedRatherThanPassing() {
        val attachments = definition.muscles.sumOf { muscle -> muscle.heads.sumOf { it.attachments.size } }
        val report = AnatomyDefinitionCheck.check(definition)

        assertEquals(
            "every attachment must be accounted for by the provenance report",
            attachments,
            report.frameUnverified.size
        )
        assertTrue(
            "no shipped attachment records its authoring frame, so the report must say so",
            report.frameUnverified.all { it.isNotBlank() }
        )
    }

    @Test
    fun theShippedLibraryDeclaresTheCanonicalInstances() {
        // Behaviour, not inventory: 8 muscles × 2 sides = 16 instances, and biceps femoris is the only
        // two-head muscle, so the frame carries 18 paths rather than 16.
        assertEquals(16, definition.instanceCount)
        assertEquals(18, definition.headCount)
        assertEquals(
            "a multi-head muscle is the only way headCount exceeds instanceCount",
            definition.headCount - definition.instanceCount,
            definition.muscles.sumOf { (it.heads.size - 1) * MuscleSide.entries.size }
        )
    }

    @Test
    fun aMultiHeadMuscleWithoutDistinctLabelsIsRefused() {
        val broken = withMuscle("biceps_femoris") { muscle ->
            muscle.copy(heads = muscle.heads.map { it.copy(label = "") })
        }
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.HEAD_LABEL)
    }

    @Test
    fun namingAGlobalObstacleIsRefusedEvenThoughItResolves() {
        val broken = withMuscle("rectus_abdominis") { it.copy(wraps = listOf("ribcage")) }
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.GLOBAL_WRAP_NAMED)
    }

    @Test
    fun anObstacleIdThatDoesNotResolveIsRefused() {
        val broken = withMuscle("gluteus_maximus") { it.copy(wraps = listOf("hip_capsule", "no_such_obstacle")) }
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.UNRESOLVED_WRAP)
    }

    @Test
    fun aDuplicatedMuscleIdIsRefused() {
        val duplicate = definition.muscles.first()
        val broken = definition.copy(muscles = definition.muscles + duplicate)
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.DUPLICATE_ID)
    }

    @Test
    fun tendonFractionsThatOverlapThePathAreRefused() {
        val broken = withMuscle("rectus_femoris") { it.copy(tendon = TendonSpec(0.6f, 0.5f)) }
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.TENDON_FRACTION)
    }

    @Test
    fun radiusKnotsThatDoNotSpanTheWholePathAreRefused() {
        val broken = withMuscle("rectus_femoris") { it.copy(radiusKnots = listOf(0.2f to 0.02f, 1f to 0.004f)) }
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.RADIUS_KNOT)
    }

    @Test
    fun anExemptionWithoutAReviewRowIsRefused() {
        val broken = withMuscle("rectus_femoris") { muscle ->
            muscle.copy(
                contactExemptions = listOf(
                    ContactExemption("hip_capsule", 0, 0f, 0.2f, envelopeSlackMetres = 1e-6f, source = "")
                )
            )
        }
        assertIssues(AnatomyDefinitionCheck.check(broken), DefinitionIssueKind.EXEMPTION_SOURCE)
    }

    @Test
    fun anExemptionThatNamesAHeadOrObstacleTheHeadNeverSeesIsRefused() {
        val wrongHead = withMuscle("rectus_femoris") { muscle ->
            muscle.copy(
                contactExemptions = listOf(ContactExemption("hip_capsule", 3, 0f, 0.2f, source = "RV1-7"))
            )
        }
        assertIssues(AnatomyDefinitionCheck.check(wrongHead), DefinitionIssueKind.EXEMPTION_HEAD)

        val outsideUnion = withMuscle("external_oblique") { muscle ->
            muscle.copy(
                contactExemptions = listOf(ContactExemption("knee_capsule", 0, 0f, 0.2f, source = "RV1-7"))
            )
        }
        assertIssues(AnatomyDefinitionCheck.check(outsideUnion), DefinitionIssueKind.EXEMPTION_OBSTACLE)
    }

    @Test
    fun aWellFormedExemptionPasses() {
        val accepted = withMuscle("rectus_femoris") { muscle ->
            muscle.copy(
                contactExemptions = listOf(ContactExemption("hip_capsule", 0, 0f, 0.2f, 1e-6f, "RV1-7"))
            )
        }
        assertTrue(
            "a sourced exemption inside the head's own union must not be reported",
            AnatomyDefinitionCheck.check(accepted).isClean
        )
    }

    @Test
    fun theUnionCarriesGlobalsPlusTheMusclesOwnObstaclesInDefinitionOrder() {
        val muscle = definition.muscle("gluteus_maximus")!!
        val ids = definition.union(muscle).map { it.id }
        assertEquals(listOf("ribcage", "hip_capsule"), ids)
        assertEquals(
            "a muscle with no wraps still sees the global obstacles",
            listOf("ribcage"),
            definition.union(definition.muscle("erector_spinae")!!).map { it.id }
        )
    }

    @Test
    fun everySegmentResolvesOnBothSidesAndTheTwoSidesAreMirrors() {
        for (segment in Segment.entries) {
            val a = MuscleSide.A.instance(segment)
            val p = MuscleSide.P.instance(segment)
            assertEquals("$segment belongs to side A", segment, a.segment)
            assertEquals("$segment belongs to side P", segment, p.segment)

            if (a == p) {
                assertNull("a central segment has one instance, so it has no mirror", SegmentId.mirror(a))
            } else {
                assertEquals(
                    "$segment's side instances must be each other's mirror",
                    p,
                    SegmentId.mirror(a)
                )
            }
        }
    }

    @Test
    fun theArmsUseTheAnchorsSuffixesAndTheLegsTheFrontBackOnes() {
        // A side is not one letter across the rig: arms are _A/_P, legs _F/_B, and both describe the
        // same physical side. A resolver that assumes one suffix family silently picks the far limb.
        assertEquals(SegmentId.HUMERUS_A, MuscleSide.A.instance(Segment.HUMERUS))
        assertEquals(SegmentId.HUMERUS_P, MuscleSide.P.instance(Segment.HUMERUS))
        assertEquals(SegmentId.FEMUR_F, MuscleSide.A.instance(Segment.FEMUR))
        assertEquals(SegmentId.FEMUR_B, MuscleSide.P.instance(Segment.FEMUR))
        assertEquals(SegmentId.THORAX, MuscleSide.P.instance(Segment.THORAX))
    }

    @Test
    fun recordingTheAuthoringFrameIsTheOnlyWayAnAttachmentLeavesTheUnverifiedList() {
        val before = AnatomyDefinitionCheck.check(definition).frameUnverified.size
        val resolved = withMuscle("rectus_femoris") { muscle ->
            muscle.copy(
                heads = muscle.heads.map { head ->
                    head.copy(origin = head.origin.copy(frame = AttachmentFrame.DERIVED_SEGMENT))
                }
            )
        }
        assertEquals(
            "one recorded attachment removes exactly one entry and clears no others",
            before - 1,
            AnatomyDefinitionCheck.check(resolved).frameUnverified.size
        )
    }

    private fun withMuscle(id: String, change: (MuscleDefinition) -> MuscleDefinition): AnatomyDefinition {
        val muscles = definition.muscles.map { if (it.id == id) change(it) else it }
        return definition.copy(muscles = muscles)
    }

    private fun assertIssues(report: DefinitionReport, kind: DefinitionIssueKind) {
        assertFalse("expected a $kind issue, the definition was clean", report.isClean)
        assertTrue("expected a $kind issue, was ${report.issues}", report.issues.any { it.kind == kind })
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
