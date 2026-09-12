package com.monkfitness.app

import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.ProjectedPoint
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportMath
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.poses.AirSquatPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * **B-4 — the `SupportPoint ↔ Joint` mapping has ONE production authority, and it is the canonical
 * A/F ↔ LEFT · P/B ↔ RIGHT convention.**
 *
 * Before B-4 the repository defined this relation three times with two contradictory conventions:
 * `SupportMath.jointsForContactMap` (LEFT = B/P family) and
 * `SkeletonPoseFinalizer.footSupportPointFor`/`toesSupportPointFor` (RIGHT_FOOT = the F foot) on one
 * side, `SkeletonPoseFinalizer.contactJointsFor` + `declaredHandSupportPoint` (LEFT = A/F family) on
 * the other. The finalizer's two foot maps were inverses of *each other's* consumers: a declaration
 * for one physical foot gated the support plane of the other, and its plane was derived from the
 * other foot's joints.
 *
 * The authority decision (evidence, in priority order — see the B-4 record in
 * `docs/STABILIZATION_AUDIT.md`):
 * 1. **`docs/ENGINE.md` §4 "Joint naming: A/P and F/B"** (ACTIVE architecture doc): "A = active /
 *    foreground limb (left), P = passive / background limb (right) for the arms and hands. F =
 *    foreground, B = background for the legs and feet."
 * 2. Authored geometry: `BasePose.buildShoulders` puts SHOULDER_A at −Z and SHOULDER_P at +Z,
 *    `buildPelvis` puts HIP_F at −Z and HIP_B at +Z, so **A and F are one physical side** and **P
 *    and B the other**; the production `Camera` (yaw 1.19, Z → screen X) renders the −Z (A/F) family
 *    on screen-left, i.e. the near/foreground limb the convention calls left.
 * 3. Pose witnesses: `BirdDogPose` ("right side -> left arm (A) + right leg (B)"),
 *    `PikePushUpPose` ("Right-Side (Side B)"), `WidePushUpPose` ("LEFT_HAND (HAND_A)"),
 *    `IsometricSidePlankPose` (the down-side support limbs are SHOULDER_P/HIP_B and it declares
 *    RIGHT_FOREARM/RIGHT_FOOT), `ARCHITECTURAL_AUDIT_SKELETON_MODEL.md`, and `ConstraintSolver`'s
 *    HAND_A → SHOULDER_A/ELBOW_A chain map.
 * 4. The engine's own hand path (`declaredHandSupportPoint` + `contactJointsFor`) already followed
 *    this convention — the *feet* were the outlier, so the hand path is the internal cross-check.
 *
 * Groups: **A** canonical mapping · **C** contradiction guard (static, by structure/content) ·
 * **D** side semantics. Group B (production behaviour) lives in `SupportPointSideConsumptionTest`.
 */
class SupportPointMappingAuthorityTest {

    // ------------------------------------------------------------------
    // A. THE canonical mapping, family by family
    // ------------------------------------------------------------------

    /**
     * The two legs: `*_FOOT` is the whole-foot support (anchored at the ankle) and `*_TOES` the
     * toe-end support (anchored at the toe) — both over the SAME `{ankle, heel, toe}` triple of the
     * SAME physical leg, the F leg for `LEFT_*` and the B leg for `RIGHT_*`.
     */
    @Test
    fun footAndToesSupportPointsNameTheCanonicalLegFamily() {
        assertEquals(
            "LEFT_FOOT is the whole-foot support of the model's LEFT (F) leg",
            listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
            SupportMath.jointsFor(SupportPoint.LEFT_FOOT)
        )
        assertEquals(
            "RIGHT_FOOT is the whole-foot support of the model's RIGHT (B) leg",
            listOf(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B),
            SupportMath.jointsFor(SupportPoint.RIGHT_FOOT)
        )
        assertEquals(
            "LEFT_TOES is the toe-end support of the same LEFT (F) leg",
            listOf(Joint.TOE_F, Joint.ANKLE_F, Joint.HEEL_F),
            SupportMath.jointsFor(SupportPoint.LEFT_TOES)
        )
        assertEquals(
            "RIGHT_TOES is the toe-end support of the same RIGHT (B) leg",
            listOf(Joint.TOE_B, Joint.ANKLE_B, Joint.HEEL_B),
            SupportMath.jointsFor(SupportPoint.RIGHT_TOES)
        )
        assertEquals(
            "a whole-foot support is anchored at its ankle",
            Joint.ANKLE_F, SupportMath.anchorJointFor(SupportPoint.LEFT_FOOT)
        )
        assertEquals(
            "a toe-end support is anchored at its toe",
            Joint.TOE_F, SupportMath.anchorJointFor(SupportPoint.LEFT_TOES)
        )
    }

    /** Knees, hands, elbows and forearms: every paired point names one limb family, never a mix. */
    @Test
    fun kneeHandElbowAndForearmSupportPointsNameTheCanonicalLimbFamily() {
        assertEquals(listOf(Joint.KNEE_F), SupportMath.jointsFor(SupportPoint.LEFT_KNEE))
        assertEquals(listOf(Joint.KNEE_B), SupportMath.jointsFor(SupportPoint.RIGHT_KNEE))
        assertEquals(
            listOf(Joint.HAND_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A),
            SupportMath.jointsFor(SupportPoint.LEFT_HAND)
        )
        assertEquals(
            listOf(Joint.HAND_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P),
            SupportMath.jointsFor(SupportPoint.RIGHT_HAND)
        )
        assertEquals(listOf(Joint.ELBOW_A), SupportMath.jointsFor(SupportPoint.LEFT_ELBOW))
        assertEquals(listOf(Joint.ELBOW_P), SupportMath.jointsFor(SupportPoint.RIGHT_ELBOW))
        assertEquals(
            "a forearm support spans the SAME arm's elbow→hand (never both arms)",
            listOf(Joint.ELBOW_A, Joint.HAND_A),
            SupportMath.jointsFor(SupportPoint.LEFT_FOREARM)
        )
        assertEquals(
            listOf(Joint.ELBOW_P, Joint.HAND_P),
            SupportMath.jointsFor(SupportPoint.RIGHT_FOREARM)
        )
    }

    /** The core support points are the only side-independent entries; `CUSTOM` invents no joint. */
    @Test
    fun coreSupportPointsAreSideIndependentAndCustomIsOpaque() {
        for (point in listOf(SupportPoint.HIPS, SupportPoint.BACK, SupportPoint.PELVIS)) {
            assertEquals(
                "$point spans the whole pelvis region (both hips), anchored at the pelvis",
                listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B),
                SupportMath.jointsFor(point)
            )
        }
        assertEquals(
            "CUSTOM is an opaque caller-owned point: the engine must not invent a joint for it",
            emptyList<Joint>(),
            SupportMath.jointsFor(SupportPoint.CUSTOM)
        )
    }

    /**
     * The general form of the convention: **no paired support point crosses the model's side
     * boundary.** This catches a swapped entry for ANY support point, not just the ones enumerated
     * above — a single `LEFT_* to listOf(Joint.*_B)` entry fails here.
     */
    @Test
    fun noPairedSupportPointNamesAJointOfTheOppositeLimbFamily() {
        for (point in SupportPoint.entries) {
            val joints = SupportMath.jointsFor(point)
            when {
                point == SupportPoint.CUSTOM -> assertTrue(
                    "CUSTOM is opaque: the engine must not invent a joint family for it, but names $joints",
                    joints.isEmpty()
                )
                point.name.startsWith("LEFT_") -> assertTrue(
                    "$point must name only *_A/_F (the model's LEFT) joints, but names $joints",
                    joints.none { it.name.endsWith("_B") || it.name.endsWith("_P") }
                )
                point.name.startsWith("RIGHT_") -> assertTrue(
                    "$point must name only *_P/_B (the model's RIGHT) joints, but names $joints",
                    joints.none { it.name.endsWith("_F") || it.name.endsWith("_A") }
                )
                else -> {
                    assertEquals(
                        "$point is a core support point: it spans both hips and is anchored at the pelvis",
                        listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B),
                        joints
                    )
                }
            }
        }
    }

    /**
     * The declaration family an ankle/hand resolves over is the canonical map's inverse — that is
     * what makes B-3's "declaration family" resolution unable to disagree with the side convention.
     * Precedence (whole kind first) is the order the extremity derivation has always used.
     */
    @Test
    fun declarationFamilyIsTheCanonicalInverseInWholeKindFirstPrecedence() {
        assertEquals(
            listOf(SupportPoint.LEFT_FOOT, SupportPoint.LEFT_TOES),
            SupportMath.supportPointsFor(Joint.ANKLE_F)
        )
        assertEquals(
            listOf(SupportPoint.RIGHT_FOOT, SupportPoint.RIGHT_TOES),
            SupportMath.supportPointsFor(Joint.ANKLE_B)
        )
        assertEquals(
            listOf(SupportPoint.LEFT_HAND, SupportPoint.LEFT_FOREARM),
            SupportMath.supportPointsFor(Joint.HAND_A)
        )
        assertEquals(
            listOf(SupportPoint.RIGHT_HAND, SupportPoint.RIGHT_FOREARM),
            SupportMath.supportPointsFor(Joint.HAND_P)
        )
        assertEquals(
            "a joint no support point is defined over resolves to no family",
            emptyList<SupportPoint>(),
            SupportMath.supportPointsFor(Joint.CHEST)
        )
    }

    /** The map and its inverse are exact inverses of each other, in both directions. */
    @Test
    fun theCanonicalMapAndItsInverseAgreeForEveryJointAndPoint() {
        for (joint in Joint.entries) {
            for (point in SupportMath.supportPointsFor(joint)) {
                assertTrue(
                    "$point was resolved for $joint but does not name it: ${SupportMath.jointsFor(point)}",
                    SupportMath.jointsFor(point).contains(joint)
                )
            }
        }
        for (point in SupportPoint.entries) {
            for (joint in SupportMath.jointsFor(point)) {
                assertTrue(
                    "$joint is claimed by $point but the inverse index does not resolve it: " +
                        "${SupportMath.supportPointsFor(joint)}",
                    SupportMath.supportPointsFor(joint).contains(point)
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // D. Side semantics — WHICH physical limb LEFT_*/RIGHT_* denote
    // ------------------------------------------------------------------

    /**
     * The semantic decision, encoded (not read back out of the implementation): the A/F limb family
     * is one physical side of the authored body and the P/B family the other, the production
     * projection renders the A/F (left) family on screen-left, and `LEFT_*`/`RIGHT_*` name exactly
     * those families. `AirSquatPose` is a symmetric production pose, so both sides are authored as
     * mirror images by `buildPelvis`/`buildShoulders`.
     */
    @Test
    fun leftSupportPointsNameTheModelsLeftLimbFamily() {
        val pose = AirSquatPose()
        val frame = SkeletonPose().apply {
            copyFrom(SkeletonPipeline(def).produceFrame(pose, context(0.5f)).pose)
        }

        val ankleF = frame.getJoint(Joint.ANKLE_F).z
        val ankleB = frame.getJoint(Joint.ANKLE_B).z
        val handA = frame.getJoint(Joint.HAND_A).z
        val handP = frame.getJoint(Joint.HAND_P).z
        val elbowA = frame.getJoint(Joint.ELBOW_A).z
        val elbowP = frame.getJoint(Joint.ELBOW_P).z

        assertTrue("the two legs must occupy opposite lateral sides: $ankleF / $ankleB", ankleF * ankleB < 0f)
        assertTrue("the two arms must occupy opposite lateral sides: $handA / $handP", handA * handP < 0f)
        assertTrue("A (LEFT arm family) shares the F (LEFT leg family) side: $handA / $ankleF", handA * ankleF > 0f)
        assertTrue("P (RIGHT arm family) shares the B (RIGHT leg family) side: $handP / $ankleB", handP * ankleB > 0f)
        assertTrue("the elbow family follows its hand: $elbowA / $elbowP", elbowA * ankleF > 0f && elbowP * ankleB > 0f)

        // The production projection must place the A/F family on screen-left (the camera yaw is what
        // makes "-Z" mean "left" in the rendered frame; assert it separates the sides at all).
        val camera = Camera(pose.metadata.camera)
        assertTrue("yaw must separate the sides on screen (yaw=${camera.yaw})", abs(sin(camera.yaw)) > 0.1f)
        val screenLeft = project(camera, frame.getJoint(Joint.ANKLE_F))
        val screenRight = project(camera, frame.getJoint(Joint.ANKLE_B))
        assertTrue(
            "LEFT_FOOT's leg (the A/F family) must render to the left of RIGHT_FOOT's leg " +
                "($screenLeft vs $screenRight)",
            screenLeft < screenRight
        )

        // And the mapping that carries that meaning.
        assertEquals(Joint.ANKLE_F, SupportMath.anchorJointFor(SupportPoint.LEFT_FOOT))
        assertEquals(Joint.KNEE_F, SupportMath.anchorJointFor(SupportPoint.LEFT_KNEE))
        assertEquals(Joint.HAND_A, SupportMath.anchorJointFor(SupportPoint.LEFT_HAND))
        assertEquals(Joint.ELBOW_A, SupportMath.anchorJointFor(SupportPoint.LEFT_FOREARM))
        assertEquals(Joint.ANKLE_B, SupportMath.anchorJointFor(SupportPoint.RIGHT_FOOT))
        assertEquals(Joint.KNEE_B, SupportMath.anchorJointFor(SupportPoint.RIGHT_KNEE))
        assertEquals(Joint.HAND_P, SupportMath.anchorJointFor(SupportPoint.RIGHT_HAND))
        assertEquals(Joint.ELBOW_P, SupportMath.anchorJointFor(SupportPoint.RIGHT_FOREARM))
    }

    // ------------------------------------------------------------------
    // C. Contradiction guard — static, by structure/content
    // ------------------------------------------------------------------

    /**
     * **Exactly one production file may define a `SupportPoint ↔ Joint` pair.** The detector is
     * structural: a line that names a support point and a joint on the *same* line in a mapping
     * shape (`SupportPoint.X to/-> … Joint.Y`, or the reverse) is a mapping definition site. A
     * second map — in any file, under any name — fails here, which is the regression this whole
     * finding is about.
     */
    @Test
    fun exactlyOneProductionFileDefinesTheSupportPointToJointMapping() {
        val mappingShape = Regex(
            """SupportPoint\.[A-Z_]+.*(->|\bto\b).*Joint\.[A-Z_]+|Joint\.[A-Z_]+.*(->|\bto\b).*SupportPoint\.[A-Z_]+"""
        )
        val sites = productionSources()
            .flatMap { file -> file.readLines().filter { mappingShape.containsMatchIn(it) }.map { file.name } }
            .groupingBy { it }
            .eachCount()
        val definedEntries = SupportPoint.entries.count { SupportMath.jointsFor(it).isNotEmpty() }
        assertEquals(
            "the canonical SupportPoint ↔ Joint mapping must be defined in exactly one production file " +
                "(SupportMath.kt) — a second definition site is how the B-4 contradiction arose",
            setOf("SupportMath.kt"),
            sites.keys
        )
        assertEquals(
            "every mapped support point must appear as exactly one mapping entry",
            definedEntries,
            sites["SupportMath.kt"]
        )
    }

    /**
     * **No production line may pair a support point with a joint of the other limb family.** Unlike
     * the definition-site check above this is a *content* check over every co-mention of a support
     * point and a joint anywhere in `src/main`, so a mapping can be reintroduced through a helper,
     * an `if`, a `when` branch or a data structure this detector has no shape for — and it is exactly
     * the assertion the pre-fix tree fails: the finalizer's two foot maps and `SupportMath`'s map
     * all contained inverted pairs (`LEFT_FOOT`↔`ANKLE_B`, `ANKLE_F`↔`RIGHT_FOOT`, `LEFT_HAND`↔
     * `HAND_P`, `LEFT_FOREARM`↔`ELBOW_P`).
     */
    @Test
    fun noProductionLinePairsASupportPointWithTheOppositeLimbFamily() {
        val supportPointToken = Regex("""SupportPoint\.([A-Z_]+)""")
        val jointToken = Regex("""\bJoint\.([A-Z_]+)""")
        val offenders = mutableListOf<String>()
        for (file in productionSources()) {
            file.readLines().forEachIndexed { index, line ->
                val points = supportPointToken.findAll(line).map { it.groupValues[1] }.toList()
                val joints = jointToken.findAll(line).map { it.groupValues[1] }.toList()
                if (points.isEmpty() || joints.isEmpty()) return@forEachIndexed
                for (p in points) {
                    val point = SupportPoint.entries.firstOrNull { it.name == p } ?: continue
                    for (j in joints) {
                        val joint = Joint.entries.firstOrNull { it.name == j } ?: continue
                        val canonical = SupportMath.jointsFor(point).contains(joint) ||
                            SupportMath.supportPointsFor(joint).contains(point)
                        if (!canonical) {
                            offenders.add("${file.name}:${index + 1}: $p ↔ $j — $line".trim())
                        }
                    }
                }
            }
        }
        assertTrue(
            "every support point ↔ joint pair in production must come from the canonical mapping; " +
                "these contradict it:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun project(camera: Camera, v: com.monkfitness.app.animation.Vector3): Float {
        val out = ProjectedPoint()
        camera.project(v, 1000f, 1000f, out)
        return out.x
    }

    private fun productionSources(): List<File> {
        var dir = File(System.getProperty("user.dir") ?: ".")
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate app module root from user.dir")
        return File(root, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }
}
