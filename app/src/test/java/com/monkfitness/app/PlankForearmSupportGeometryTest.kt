package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.IsometricSidePlankPose
import com.monkfitness.app.poses.StaticForearmPlankPose
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * **B-7 — the declared `*_FOREARM` support contact is ONE physical chain, on the mat.**
 *
 * The engine contract (`docs/ARCHITECTURE_V2.md` §4.1 support declaration, `docs/BIOMECHANICS.md`)
 * is that every body point a pose declares as resting on the environment actually rests on it. For
 * the forearm family that declaration names TWO joints — `SupportMath.jointsFor(*_FOREARM)` is the
 * contact's own `elbow → hand` pair — and they are the two ends of one physical forearm. A pose that
 * plants its hand and puts its elbow 38–45 units under the mat has not planted a forearm; it has
 * planted a hand and driven the elbow through the floor.
 *
 * ## The defect this file gates (P11 whole-system audit §M2, `docs/STABILIZATION_AUDIT.md` B-3/B-6)
 *
 * Measured on the pre-fix tree (`origin/main` @ `e075c6e`), through the production pipeline, at
 * `progress = 0.5`:
 *
 * | pose | shoulder | elbow | hand | elbow vs. the mat |
 * |---|---|---|---|---|
 * | `StaticForearmPlankPose` | `36.48` | `−35.81` | `15.00` | ~51 units BELOW |
 * | `IsometricSidePlankPose` (down side, `SHOULDER_P`) | `15.10` | `−37.86` | `15.00` | ~53 units BELOW |
 *
 * B-6's invariant (test-only) attributed those two pose/contact pairs rather than hiding them. B-7
 * re-authored the plant: the support chain is now derived from its own contact (the elbow under its
 * shoulder, the hand one forearm length ahead of it, both on the mat), the shoulder is propped at the
 * top of that pillar, and the trunk hangs off the shoulder. The elbow is therefore a *consequence* of
 * the arm chain the engine solves, not a number moved onto the plane.
 *
 * ## What each test proves
 *
 *  1. [forearmContactIsPlantedFlatOnItsDeclaredSurface] — the invariant the declaration actually
 *     makes: for both planks, every sampled progress, both frame conditions, `ELBOW_*` is not below
 *     the declared surface by more than the engine's unchanged 2-unit band, `HAND_*` is not either,
 *     and the two sit LEVEL with each other (a forearm is flat; a hand planted while its elbow is 50
 *     units lower is not).
 *  2. [everyDeclaredForearmResolvesThroughTheOneCanonicalMap] — anti-vacuity: the contact resolves to
 *     a non-empty joint family from the ONE canonical map (`SupportMath.jointsFor` — no test-local
 *     copy of the `SupportPoint → Joint` relation), the family is the support's own chain (its anchor
 *     joint plus the contact-layer joints of the same limb family), and the declaration reaches the
 *     *published* frame's carrier (`SkeletonPose.supportedPoints`) that this file measures.
 *  3. [sampledFramesArePublishedGeometryNotAReusedBuffer] — anti-aliasing: the frames measured are
 *     distinct, immutable snapshots of what the pipeline published, and frame N does not change when
 *     frame N+1 is produced.
 *  4. [repairedArmChainIsReachableAndUnclamped] — the production carrier's own readings:
 *     `maxIkClampAmount == 0` and `boneLengthsVerified == true` at every sample, the realised segments
 *     equal the definition's `upperArmLength`/`forearmLength` exactly, `limbTargets` carries the plant
 *     the pose declared, and the elbow's interior angle stays inside the arm constraint's band —
 *     `~90°` with the upper arm vertical at the braced hold (BPS §6/§11), never a hyperextension or a
 *     stall.
 *  5. [supportDeclarationsAndUnaffectedPosesAreUnchanged] — regression preservation: the two poses'
 *     declared contact sets are the pinned data they were, the `*_FOREARM` family is still one
 *     elbow + one hand of the same limb family, and the other **49** production pose classes publish
 *     byte-identical geometry to the pre-B-7 tree (a digest over every joint of every sampled frame,
 *     measured identical on pristine `origin/main` and on this branch).
 */
class PlankForearmSupportGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The engine's penetration band (unchanged since B-6): a declared contact joint may not sit
     *  more than this far below the surface its own declaration rests on. */
    private val penetrationBand = 2.0f

    /** How far the forearm's two contacts may differ in height before the forearm is not flat. */
    private val flatForearmBand = 1.5f

    /** The frame conditions the plant must hold under (a cold first frame and an advancing one). */
    private enum class FrameCondition { COLD, PLAYING }

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    /** A frame captured BY VALUE: the pipeline publishes the Finalizer's reused output buffer. */
    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /** The forearm contact each pose declares as its support (the declaration is authoritative). */
    private val forearmContacts = mapOf(
        "StaticForearmPlankPose" to listOf(SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM),
        "IsometricSidePlankPose" to listOf(SupportPoint.RIGHT_FOREARM)
    )

    /**
     * All sampled frames of one pose under both frame conditions, captured by value, paired with the
     * condition and progress they came from.
     */
    private fun frames(name: String): List<Triple<FrameCondition, Float, SkeletonPose>> {
        val out = mutableListOf<Triple<FrameCondition, Float, SkeletonPose>>()
        // COLD — the genuinely cold first frame: a fresh pose instance on a fresh pipeline.
        for (p in samples) {
            val cold = SkeletonPipeline(def).produceFrame(MotionProbe.build(name), ctx(p)).pose
            out.add(Triple(FrameCondition.COLD, p, snapshot(cold)))
        }
        // PLAYING — one builder + one pipeline advancing 0 -> 1, frames captured by value.
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)
        for (p in samples) {
            val frame = pipeline.produceFrame(builder, ctx(p)).pose
            out.add(Triple(FrameCondition.PLAYING, p, snapshot(frame)))
        }
        return out
    }

    /**
     * The surface a declared contact rests on, by the engine's own rule
     * (`SkeletonPoseFinalizer.supportPlaneNormalFor`): one plane per contact, resolved from the
     * contact's canonical joint centroid — a prop's top when that centroid lies inside its footprint,
     * otherwise the ground plane. Resolving per joint would split one contact across two surfaces.
     */
    private fun contactSurfaceY(frame: SkeletonPose, point: SupportPoint): Float {
        val env = frame.environment
        val joints = SupportMath.jointsFor(point).map { frame.getJoint(it) }
        check(joints.isNotEmpty()) { "declared support point $point has no canonical joint family" }
        val cx = joints.sumOf { it.x.toDouble() }.toFloat() / joints.size
        val cz = joints.sumOf { it.z.toDouble() }.toFloat() / joints.size
        var surface = env.ground.level
        for (prop in env.props) {
            val fp = footprint(prop) ?: continue
            if (cx in (fp[0] - fp[3])..(fp[0] + fp[3]) && cz in (fp[2] - fp[5])..(fp[2] + fp[5])) {
                when (prop) {
                    is BoxProp, is StepProp, is BenchProp -> surface = fp[1] + fp[4]
                    is WallProp -> { /* a wall supports on its face; Y stays the ground reference */ }
                }
            }
        }
        return surface
    }

    private fun footprint(prop: EnvironmentProp): FloatArray? = when (prop) {
        is BoxProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is StepProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is BenchProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is WallProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
    }

    /** The elbow/hand of a declared forearm contact, resolved through the canonical map. */
    private fun elbowAndHand(point: SupportPoint, frame: SkeletonPose): Pair<Vector3, Vector3> {
        val family = SupportMath.jointsFor(point)
        val elbow = family.firstOrNull { it.name.startsWith("ELBOW_") }
        val hand = family.firstOrNull { it.name.startsWith("HAND_") }
        assertNotNull("$point must resolve an elbow joint through the canonical map", elbow)
        assertNotNull("$point must resolve a hand joint through the canonical map", hand)
        return frame.getJoint(elbow!!) to frame.getJoint(hand!!)
    }

    private fun side(joint: Joint): String = joint.name.substringAfterLast('_')

    private fun dist(a: Vector3, b: Vector3) = sqrt(
        (a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2)
    )

    /** Interior angle at [vertex] of the chain [a] -> [vertex] -> [b], in degrees. */
    private fun interiorAngleDeg(a: Vector3, vertex: Vector3, b: Vector3): Float {
        val l1 = dist(a, vertex); val l2 = dist(vertex, b); val base = dist(a, b)
        return Math.toDegrees(
            acos(((l1 * l1 + l2 * l2 - base * base) / (2f * l1 * l2)).coerceIn(-1f, 1f).toDouble())
        ).toFloat()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. The invariant: a declared forearm rests FLAT on its own surface
    // ---------------------------------------------------------------------------------------------

    @Test
    fun forearmContactIsPlantedFlatOnItsDeclaredSurface() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((name, contacts) in forearmContacts) {
            for ((condition, progress, frame) in frames(name)) {
                for (point in contacts) {
                    val surface = contactSurfaceY(frame, point)
                    val (elbow, hand) = elbowAndHand(point, frame)
                    val elbowDelta = elbow.y - surface
                    val handDelta = hand.y - surface
                    val flatness = abs(elbow.y - hand.y)
                    rows.add(
                        "%s %s p=%.2f %s surface=%.2f ELBOW.y=%.3f (%+.3f) HAND.y=%.3f (%+.3f) flatness=%.3f"
                            .format(name, condition, progress, point, surface, elbow.y, elbowDelta, hand.y, handDelta, flatness)
                    )
                    if (elbowDelta < -penetrationBand) {
                        failures.add(
                            "%s %s p=%.2f: declared %s elbow is %.3f BELOW its support surface"
                                .format(name, condition, progress, point, elbowDelta)
                        )
                    }
                    if (handDelta < -penetrationBand) {
                        failures.add(
                            "%s %s p=%.2f: declared %s hand is %.3f BELOW its support surface"
                                .format(name, condition, progress, point, handDelta)
                        )
                    }
                    // The declared chain is one FLAT forearm: a contact whose hand is planted while its
                    // elbow hangs (or floats) far away is not a planted forearm.
                    if (flatness > flatForearmBand) {
                        failures.add(
                            "%s %s p=%.2f: declared %s is not flat — ELBOW.y=%.3f vs HAND.y=%.3f (%.3f apart, band %.1f)"
                                .format(name, condition, progress, point, elbow.y, hand.y, flatness, flatForearmBand)
                        )
                    }
                }
            }
        }
        assertTrue(
            "declared forearm contacts are not planted on their own support surface:\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Anti-vacuity: the canonical resolution, and it is the PUBLISHED frame being measured
    // ---------------------------------------------------------------------------------------------

    @Test
    fun everyDeclaredForearmResolvesThroughTheOneCanonicalMap() {
        for ((name, contacts) in forearmContacts) {
            val builder = MotionProbe.build(name)
            val declared = builder.metadata.support.contacts.map { it.point }.toSet()
            assertTrue(
                "$name must declare its forearm contacts (found $declared)",
                declared.containsAll(contacts)
            )
            for (point in contacts) {
                val family = SupportMath.jointsFor(point)
                assertTrue(
                    "$name declares $point but the ONE canonical map resolves no joint — a declared " +
                        "contact no consumer can resolve (the B-6 vacuity class)",
                    family.isNotEmpty()
                )
                // The family is this support's OWN chain: its anchor joint plus the contact-layer
                // joints of the SAME limb family (never a cross-family pair, B-4).
                val anchor = SupportMath.anchorJointFor(point)
                assertEquals("the family's first entry is the support's anchor joint", anchor, family.first())
                val families = family.map { side(it) }.distinct()
                assertEquals("$point must resolve joints of ONE limb family, found $families", 1, families.size)
                assertEquals("$point must resolve exactly the forearm's two ends", 2, family.size)
                assertTrue(
                    "$point must resolve an elbow and a hand",
                    family.any { it.name.startsWith("ELBOW_") } && family.any { it.name.startsWith("HAND_") }
                )
            }

            // The declaration must reach the PUBLISHED frame's carrier — this file measures frames the
            // pipeline published, not the pose's authoring state.
            for ((_, progress, frame) in frames(name)) {
                assertTrue(
                    "$name p=$progress: the published frame must carry the declared support model " +
                        "(published=${frame.supportedPoints.toSet()}, declared=$declared)",
                    frame.supportedPoints.toSet() == declared
                )
            }
        }
    }

    @Test
    fun sampledFramesArePublishedGeometryNotAReusedBuffer() {
        val name = "StaticForearmPlankPose"
        val captured = frames(name)
        assertEquals(samples.size * 2, captured.size)

        // Distinct objects: holding frames by reference would alias every sample to the last one.
        val identities = captured.map { System.identityHashCode(it.third) }
        assertEquals(
            "every sampled frame must be a distinct object (the pipeline publishes a reused buffer)",
            captured.size, identities.distinct().size
        )

        // A frame read before later frames were produced must still read the same afterwards.
        val first = captured.first().third
        val chestBefore = first.getJoint(Joint.CHEST).y
        val elbowBefore = first.getJoint(Joint.ELBOW_A).y
        val playing = captured.filter { it.first == FrameCondition.PLAYING }
        for ((_, p, _) in playing) {
            SkeletonPipeline(def).produceFrame(MotionProbe.build(name), ctx(p))
        }
        assertEquals("the first captured frame must be immutable", chestBefore, first.getJoint(Joint.CHEST).y, 0f)
        assertEquals("the first captured frame must be immutable", elbowBefore, first.getJoint(Joint.ELBOW_A).y, 0f)

        // …and the samples really are different observations (this pose's hips travel).
        val hipHeights = playing.map { it.third.getJoint(Joint.PELVIS).y }
        assertTrue(
            "the sampled frames must be genuinely different geometry, not one frame measured five times " +
                "(PELVIS heights: $hipHeights)",
            hipHeights.distinct().size >= 2 && hipHeights.max() - hipHeights.min() > 1f
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The repaired chain: reachable, unclamped, and the chain the BPS describes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun repairedArmChainIsReachableAndUnclamped() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((name, contacts) in forearmContacts) {
            for ((condition, progress, frame) in frames(name)) {
                if (frame.maxIkClampAmount != 0f) {
                    failures.add("$name $condition p=$progress: maxIkClampAmount=${frame.maxIkClampAmount} (a solve was clamped)")
                }
                if (!frame.boneLengthsVerified) {
                    failures.add("$name $condition p=$progress: boneLengthsVerified=false (a solved chain lost a segment length)")
                }
                for (point in contacts) {
                    val family = SupportMath.jointsFor(point)
                    val elbowJoint = family.first { it.name.startsWith("ELBOW_") }
                    val handJoint = family.first { it.name.startsWith("HAND_") }
                    val shoulderJoint = Joint.valueOf("SHOULDER_" + side(elbowJoint))
                    val shoulder = frame.getJoint(shoulderJoint)
                    val elbow = frame.getJoint(elbowJoint)
                    val hand = frame.getJoint(handJoint)

                    val upper = dist(shoulder, elbow)
                    val fore = dist(elbow, hand)
                    val angle = interiorAngleDeg(shoulder, elbow, hand)
                    val shoulderHand = dist(shoulder, hand)
                    val lo = SkeletonMath.minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
                    val hi = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
                    rows.add(
                        "%s %s p=%.2f %s |sh-el|=%.3f |el-hand|=%.3f elbow=%.2f° |sh-hand|=%.3f (band %.2f..%.2f) elbowY=%.3f"
                            .format(name, condition, progress, point, upper, fore, angle, shoulderHand, lo, hi, elbow.y)
                    )
                    if (abs(upper - def.upperArmLength) > 1e-3f || abs(fore - def.forearmLength) > 1e-3f) {
                        failures.add(
                            "$name $condition p=$progress: the realised chain does not hold the definition's " +
                                "segments (upper=${f(upper)} of ${def.upperArmLength}, forearm=${f(fore)} of ${def.forearmLength})"
                        )
                    }
                    if (shoulderHand < lo + 1f || shoulderHand > hi - 1f) {
                        failures.add(
                            "$name $condition p=$progress: |shoulder→hand|=${f(shoulderHand)} is on the reachable band's edge " +
                                "(${f(lo)}..${f(hi)}) — the authored target was relocated"
                        )
                    }
                    val limits = def.armIKConstraint.angularLimits
                    if (angle < limits.minFlexionDegrees || angle > limits.maxFlexionDegrees) {
                        failures.add(
                            "$name $condition p=$progress: elbow interior angle ${f(angle)}° is outside the arm " +
                                "constraint's band (${limits.minFlexionDegrees}..${limits.maxFlexionDegrees})"
                        )
                    }
                }
            }
        }
        assertTrue(
            "the repaired forearm chain is not a reachable, unclamped arm chain:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The BPS shape of the repaired chain, per pose (measured, not assumed):
     *
     *  * **Braced (`progress = 1`)**: the upper arm is VERTICAL — the elbow sits directly under its
     *    shoulder (horizontal offset `0.000`), the interior angle is `90.00°`, and both elbow and hand
     *    are exactly at the mat's planted-forearm height.
     *  * **Settled (`progress = 0`)**: the pillar leans back over its elbow by the pose's authored
     *    lean (`12°` flat plank / `20°` side plank) — the horizontal offset that keeps the deep hip
     *    settle reachable for the planted leg — and the elbow is still on the mat.
     */
    @Test
    fun bracedHoldRealizesTheVerticalPillarAndElbowUnderShoulder() {
        val bracedOffsets = mutableMapOf<String, Float>()
        val settledOffsets = mutableMapOf<String, Float>()
        for ((name, contacts) in forearmContacts) {
            for (point in contacts) {
                val family = SupportMath.jointsFor(point)
                val elbowJoint = family.first { it.name.startsWith("ELBOW_") }
                val shoulderJoint = Joint.valueOf("SHOULDER_" + side(elbowJoint))
                for ((condition, progress, frame) in frames(name)) {
                    val shoulder = frame.getJoint(shoulderJoint)
                    val elbow = frame.getJoint(elbowJoint)
                    val offset = sqrt((shoulder.x - elbow.x).pow(2) + (shoulder.z - elbow.z).pow(2))
                    when (progress) {
                        1.0f -> bracedOffsets["$name/$condition"] = offset
                        0.0f -> settledOffsets["$name/$condition"] = offset
                    }
                }
                assertEquals(
                    "$name: the braced hold's support elbow must be DIRECTLY under its shoulder " +
                        "(no lean, no drift) — offsets=$bracedOffsets",
                    0f, bracedOffsets.values.max(), 0.01f
                )
                assertTrue(
                    "$name: the settled frame's pillar lean must stay inside the authored lean " +
                        "(≤ 80·sin(20°)=27.4 units of horizontal offset) — offsets=$settledOffsets",
                    settledOffsets.values.max() <= 80f * kotlin.math.sin(Math.toRadians(20.0).toFloat()) + 0.5f
                )
                // …and the settled offset must actually be used (the lean is what makes the deep
                // settle reachable; a zero lean here would mean the plant never moved off vertical).
                assertTrue(
                    "$name: the settled frame must lean the pillar (offsets=$settledOffsets)",
                    settledOffsets.values.max() > 1f
                )
            }
        }
    }

    /** The pose declares the plant it realizes: `limbTargets` carries the hand plant on the mat. */
    @Test
    fun declaredLimbTargetsMatchThePlantedContacts() {
        for ((name, contacts) in forearmContacts) {
            for (p in samples) {
                val declared = MotionProbe.build(name).build(ctx(p)).limbTargets
                for (point in contacts) {
                    val family = SupportMath.jointsFor(point)
                    val handJoint = family.first { it.name.startsWith("HAND_") }
                    val target = declared.firstOrNull { it.joint == handJoint }
                    assertNotNull("$name p=$p must register a limb target for $handJoint", target)
                    assertTrue(
                        "$name p=$p: the declared $handJoint target must be ON the mat, was y=${target!!.world.y}",
                        abs(target.world.y - 15f) <= penetrationBand
                    )
                    assertTrue(
                        "$name p=$p: the declared $handJoint pole must be authored (non-zero)",
                        target.pole.mag() > 1e-4f
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Regression preservation
    // ---------------------------------------------------------------------------------------------

    /**
     * The support declarations of the two corrected poses, pinned exactly: B-7 changes the poses'
     * GEOMETRY, not what they declare as resting on the world (the declaration is the pose's
     * statement and stays authoritative for this pass).
     */
    @Test
    fun supportDeclarationsAreUnchanged() {
        val expected = mapOf(
            "StaticForearmPlankPose" to setOf(
                SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM,
                SupportPoint.LEFT_TOES, SupportPoint.RIGHT_TOES
            ),
            "IsometricSidePlankPose" to setOf(
                SupportPoint.RIGHT_FOREARM, SupportPoint.RIGHT_FOOT
            )
        )
        for ((name, declared) in expected) {
            val builder = MotionProbe.build(name)
            assertEquals(
                "$name: the declared support model must be unchanged by the geometry correction",
                declared, builder.metadata.support.contacts.map { it.point }.toSet()
            )
            assertEquals(
                "$name: the published frame must carry exactly that declaration at every sample",
                declared,
                MotionProbe.build(name).let { b ->
                    val pipeline = SkeletonPipeline(def)
                    pipeline.produceFrame(b, ctx(0.5f)).pose.supportedPoints.toSet()
                }
            )
        }
    }

    /**
     * The canonical mapping still names the forearm support as ONE elbow + ONE hand of ONE family
     * (B-4's authority; the relation itself is guarded by `SupportPointMappingAuthorityTest`, so this
     * only checks that B-7 did not re-point it).
     */
    @Test
    fun forearmSupportMappingIsUnchanged() {
        val pairs = listOf(
            SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM,
            SupportPoint.LEFT_ELBOW, SupportPoint.RIGHT_ELBOW,
            SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND
        )
        for (point in pairs) {
            val family = SupportMath.jointsFor(point)
            assertTrue("$point must resolve a joint family", family.isNotEmpty())
            assertEquals("$point's family must stay single-family", 1, family.map { side(it) }.distinct().size)
        }
        assertEquals(
            "LEFT_FOREARM must stay the A-family elbow+hand pair",
            listOf("ELBOW_A", "HAND_A"),
            SupportMath.jointsFor(SupportPoint.LEFT_FOREARM).map { it.name }
        )
        assertEquals(
            "RIGHT_FOREARM must stay the P-family elbow+hand pair",
            listOf("ELBOW_P", "HAND_P"),
            SupportMath.jointsFor(SupportPoint.RIGHT_FOREARM).map { it.name }
        )
    }

    /**
     * Every OTHER production pose publishes byte-identical geometry to the pre-B-7 tree: the digest
     * below covers every joint of every sampled frame of the **49** production pose classes that are
     * not the two corrected planks, and it was measured **equal** on pristine `origin/main` @
     * `e075c6e` and on this branch (see `docs/STABILIZATION_AUDIT.md`, B-7). The two planks are
     * excluded because they are the correction; their geometry is gated by the tests above.
     */
    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val excluded = forearmContacts.keys
        val digest = corpusDigest(excluded)
        assertEquals(
            "geometry of the poses outside the corrected plank family must be byte-identical to the " +
                "pre-B-7 tree (the digest covers every joint of every sampled frame of every other " +
                "production pose class); a change here means the correction leaked outside its scope",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** Every concrete production pose class in `poses/` except the corrected planks. */
    private fun corpusDigest(excluded: Set<String>): Long {
        val start = java.io.File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        var dir = start
        var moduleRoot: java.io.File? = null
        for (attempt in 0 until 8) {
            if (java.io.File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate the app module root from $start")
        val names = java.io.File(root, "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it in excluded }
            .sorted()
        assertTrue("anti-vacuity: the digest corpus must contain the other poses (found ${names.size})", names.size >= 45)

        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in samples) {
                val frame = snapshot(pipeline.produceFrame(builder, ctx(p)).pose)
                for (joint in Joint.entries) {
                    val v = frame.getJoint(joint)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.x)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.y)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.z)
                }
            }
        }
        return hash
    }

    companion object {
        /**
         * Digest of the 49 unaffected production pose classes (every joint, every sampled frame).
         * Measured identically on the unmodified baseline (`origin/main` @ `e075c6e`) and on the B-7
         * tree — i.e. the correction's geometry is confined to `StaticForearmPlankPose` and
         * `IsometricSidePlankPose`.
         *
         * **Re-baselined once, by B-8b** (`fix/b8b-thoracic-extension-target`): that change stops
         * `ThoracicExtensionPose` deriving its arm targets from the engine-owned neck node, which
         * moves that pose's **cold first frame only** (the first sample of the first pipeline this
         * loop samples) — 12 arm-chain joints, 29.9277u at ELBOW_A (p=0) up to 32.2308u at p=1, with
         * the earlier cold-frame target `(-12.000000, 253.000000)` instead of the rep's
         * `(-14.144614, 270.871796)`. Every one of the other 48 classes is byte-identical at full
         * precision, and `thoracic_extension_reps` is byte-identical on every NON-cold frame
         * (`ThoracicExtensionArmTargetTest` asserts that directly).
         *
         * **Re-baselined again by M1** (`fix/m1-stepup-geometry-support`, `StepUpPose` — the audit's
         * §3 M1 finding): that change places the step-up's planted foot on the tread its own
         * declaration names and raises the ascent to the step's height, which moves `StepUpPose`
         * only, on the frames it is up on the step (`p ∈ {0.25, 0.5, 0.75}`, 33 joints each; the
         * `PING_PONG` seam frames are byte-identical). Attribution is direct, not inferred from this
         * digest: a whole-corpus dump of **51 classes × 5 progress × every joint XYZ** (`8415` rows,
         * full float bits) measured on the pre-fix and post-fix trees differs in **exactly 99 rows,
         * all of them `StepUpPose`** — the other **50 classes are byte-identical** — and the M1 gate's
         * own digest, which excludes `StepUpPose`, is equal on both trees
         * (`M1StepUpGeometryTest.UNAFFECTED_CORPUS_DIGEST`). This guard was observed RED on this
         * change before the re-baseline (`expected:<8354470872339933400>`, the pre-fix value), which
         * is its own mutation check. The corpus and its coverage are unchanged — all 50 classes stay
         * in the digest, so any further drift in any of them still fails here.
         *
         * **Re-baselined again by M3/M5** (`fix/m3-m5-prone-trunk-geometry` off `2bb4525`): that
         * correction owns `ProneCobraStretchPose`, `SupermanPose` and `ReverseSnowAngelPose`, all
         * three of which are inside this "every other class" corpus. Observed RED on the pre-fix
         * value `-2908768886375429885` before the re-baseline. Attribution is direct, not inferred:
         * the same whole-corpus dump (51 classes × 5 progress × every joint XYZ, `8415` rows, full
         * float bits) measured on both trees differs in **exactly 377 rows, all of them those three
         * poses** (`SupermanPose` 153, `ReverseSnowAngelPose` 143, `ProneCobraStretchPose` 81) — the
         * other **48 classes are byte-identical**, which is gated by
         * `M3M5ProneTrunkGeometryTest.UNAFFECTED_CORPUS_DIGEST` (`-517042293001259057`, equal on both
         * trees with the three corrected classes excluded).
         *
         * **Re-baselined again by the M6/M7 swing/burpee correction** (`fix/m6-m7-swing-burpee-geometry`,
         * off `fc65695`): this corpus is "every pose except the two forearm planks", so it includes the two
         * poses that correction owns. Observed RED on the previous value `3799530965937589305` before the
         * re-baseline. Attribution is direct, not inferred: the whole-corpus dump (51 classes × 5 progress
         * × every joint XYZ, `8415` rows, full float bits) differs in exactly `268` rows, all of them
         * `KettlebellSwingPose` + `BurpeePose`; the other 49 classes are byte-identical, which
         * `M6M7SwingBurpeeGeometryTest.UNAFFECTED_CORPUS_DIGEST` (`-2275091341366878044`) gates directly.
         *
         * **Re-baselined again by the M8/M9/M10 support-declaration pass**
         * (`fix/m8-m9-m10-support-declaration`): the 17 classes that pass declares/ re-authors
         * (7 upper/dynamic + the stretch family + the core/hip poses) all live inside this "every
         * pose outside the plank family" corpus. Observed RED on the pre-fix value
         * `3799530965937589305`, and again on the M6/M7 value `-8819852136411858964` after the pass was
         * rebased onto the M6/M7 merge (this pass originally branched off `fc65695`), before the
         * re-baseline below. The pass's own blast-radius guard
         * (`M8M9M10SupportDeclarationTest.UNAFFECTED_CORPUS_DIGEST`) excludes exactly its 17 classes
         * and is measured equal on the pre-fix and post-fix trees.
         *
         * **Re-baselined by the M13 hamstring forward-reach correction** (`fix/m13-hamstring-reach`,
         * off `0301563`): this corpus is "every production pose outside the plank family", so it includes `HamstringStretchPose`, the one class that
         * correction owns. Observed RED on the pre-fix value `2399534090990759846` before the re-baseline (this
         * live run measured `-424882841079246328` below); the five scope digests were re-run with the pose file
         * stashed and all 50 of their tests were GREEN, so the delta is attributable to M13 and not
         * to a drifted base. Attribution is direct, not inferred from this digest: the whole-corpus
         * dump (49 registry poses × 5 progress × every joint XYZ, `245` pose-frames) differs in
         * exactly `1` frame — `hamstring_stretch_hold` at `p=0.0`, 12 arm-chain joints, max `0.8930`
         * u at `FINGERTIPS_A` — with the other `244` frames (including the subject's `p ≥ 0.05`)
         * byte-identical and `supportedPoints`/`maxIkClampAmount` unchanged everywhere. M13's own
         * blast-radius guard is `HamstringForwardReachTest.UNAFFECTED_CORPUS_DIGEST`.
         *
         * **Re-baselined by the M11/M12 limb-realization migration**
         * (`fix/m11-m12-limb-realization-migration`, off `a8d07cf`): the corpus means "every production
         * pose class outside the corrected plank family", so it contains `LatStretchPose` (M11 — the
         * canonical authored hierarchy replaces the hand-rolled tree, publishing `LUMBAR`/`CLAVICLE_*`/
         * `SCAPULA_*` instead of the world origin) and `CatCowPose` (M12 — the four-point support
         * declaration and the reachable-by-construction leg targets). Observed RED on the previous value
         * before this re-baseline. Attribution is direct, not inferred: the whole-corpus dump (`50`
         * classes × `5` samples × every joint, `8415` rows, `git stash` round-trip on the two pose files)
         * differs in exactly `95` xyz rows — `70` in `CatCowPose`, `25` in `LatStretchPose` — and the
         * other `48` classes are byte-identical. That pass's own blast-radius guard is
         * `M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST`.
                  *
         * **Re-baselined by the M15 wall/forearm contact-plane correction**
         * (`fix/m15-wallslides-wall-geometry`, off `4203fff`): this corpus contains `WallSlidesPose`, the
         * pose M15 corrects — its arm chain is now authored in the wall prop's own contact plane, its
         * elbow is placed on that plane, and the wall prop itself spans the athlete instead of stopping
         * below the pelvis. Observed RED on the previous value `8776294205745763414` before the re-baseline (this live
         * run measured `7021051649408857128`). Attribution is direct, not inferred: the whole-corpus dump (`51`
         * classes × `9` samples × every joint XYZ, plus every `maxIkClampAmount` /
         * `boneLengthsVerified` / `supportedPoints` stamp, the environment props and the declared limb
         * targets — `16524` rows — over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs in exactly `126` rows, ALL of them inside `WallSlidesPose`:
         * the two arm chains' `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*`
         * (`12` joints × `9` samples = `108`) plus the `9` `TARGETS` and `9` `ENV` rows — with the other
         * `50` classes byte-identical and the reachability stamp unchanged
         * (`maxIkClampAmount` `0.047028` on both trees). M15's own blast-radius guard is
         * `M15WallSlidesWallGeometryTest.UNAFFECTED_CORPUS_DIGEST`.
         * **Re-baselined by the B2 runner's-lunge back-knee correction**
         * (`fix/b2-wgs-back-knee-plane`, off `2fb6079`): this corpus contains
         * `DynamicWorldsGreatestStretchPose`, the pose B2 corrects. Its back leg's stance is now the
         * extension the pose's own KDoc declares — the ankle authored one full chain reach behind the
         * hip, at the definition's own floor-contact height, with the knee's bend side derived from the
         * hip→ankle chord — so the realized `KNEE_B` sits `+13.162892` ABOVE the mat instead of the
         * `−46.105583` BELOW it that T2 pinned.
         * Observed RED on the previous value `7021051649408857128` before the re-baseline (this live run measured
         * `8245693820516700285`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
         * samples × every joint XYZ = `8415` rows, taken in a pristine `origin/main` @ `2fb6079`
         * worktree and on this tree, then diffed) differs in exactly `20` rows, ALL of them inside
         * `DynamicWorldsGreatestStretchPose` (`KNEE_B`/`ANKLE_B`/`HEEL_B`/`TOE_B` × the `5` samples,
         * max `82.2520` u at `HEEL_B`), with the other `50` classes byte-identical and the pose's
         * reachability stamp unchanged (`maxIkClampAmount` `21.640945` / `13.396454` / `7.5872955` at
         * p = 0 / 0.25 / 0.5 on BOTH trees — that clamp is the pose's support arm, and B2 does not
         * touch the arms). B2's own blast-radius guard is `WorldsGreatestStretchBackKneePlaneTest`.
         *
        * **Re-baselined by the B1 diamond push-up elbow-plane correction**
        * (`fix/b1-diamond-pushup-elbow-plane`, off `2fb6079` — the T2 merge): this corpus means "every
        * production pose class except the ones this pass corrects", so it contains `DiamondPushUpPose`,
        * whose elbow pole is re-authored onto the trunk's own long axis. The inherited Z-dominant pole
        * shape is correct for a grip whose hands sit at or outside the shoulder line; the diamond grip is
        * `0.1` (the hands come to the fused base `4.6` from the midline against the shoulder joint's
        * `46`), so the pole's perpendicular residual collapsed onto the chord's downward basis vector and
        * realized the elbow `19.91` u BELOW the pose's own declared plane at the bottom of the rep
        * (measured `p = 0.5`; the pose is a pinned, attributed open item in the T2 invariant, whose entry
        * this correction removes in the same change). Observed RED on the previous value `7021051649408857128` before
        * the re-baseline (this live run measured `1520710032374707146`). Attribution is direct, not
        * inferred
        * whole-corpus dump (`51` classes x `5` samples x every joint XYZ, `8415` rows, a `git stash`
        * round-trip on the corrected pose file with `md5sum -c` on restore) differs in exactly `60` rows,
        * ALL of them inside `DiamondPushUpPose` — `ELBOW_A`/`ELBOW_P` at all five samples
        * (`19.96 ... 46.79` u) plus the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` pair
        * (<= `8e-6` u float drift at four samples, and `5.29` / `10.57` / `19.38` u at `p = 0.5`, where
        * the engine's planted-hand flattening now fires because the elbow is above the hand) — with the
        * other `50` classes byte-identical. B1's own regression is `DiamondPushUpElbowClearanceTest`.
         *
         * **Re-measured by the B1 integration** (this branch merges `fix/b1-diamond-pushup-elbow-plane`
         * @ `29ee54b`, off `2fb6079`, onto the B2 merge `f8f8b24`): this corpus contains BOTH corrected
         * poses, so neither pass's committed value was valid on the merged tree. Observed RED on the
         * B2-merged value `8245693820516700285` before this re-baseline (this live run, with B1 integrated,
         * measured `2745352203482550303`). The digest's move from the B2-merged value is B1's own delta
         * (`DiamondPushUpPose`'s `ELBOW_A`/`ELBOW_P` plus the derived hand chain — `60` rows of the
         * whole-corpus dump, per B1's own record); from the pre-B2 value it is the union of B2's `20`
         * rows and B1's `60`.
         * **Re-baselined by the B4 supine elbow-plane correction**
         * (`fix/b4-glute-bridge-pelvic-tilt-elbow-plane`, off `ba3928b` — the B1+B2+B3 tree): this corpus
         * contains `GluteBridgePose` and `PelvicTiltPose`, the two supine poses B4 corrects. Their arms' bend
         * side is re-authored onto the poses' own lateral axis: the family's `(0, -1, ∓1)` pole spent its `-Y`
         * component on the horizontal shoulder→hand chord's DOWNWARD basis vector (measured
         * `phat_y = -0.6995 … -0.7079` against `h = 58.48 … 60.74`), realizing `ELBOW_A/P` `28.6 … 33.4` u
         * below each pose's own declared mat at EVERY phase. Both poses' elbows now lie in the floor plane
         * (`+7.1 … +12.8` u) and the engine's planted-hand flattening fires with them. Observed RED on the
         * previous value `2745352203482550303` before the re-baseline (this live run measured `-3055606113830305699`). Attribution is direct,
         * not inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows, over a
         * `git stash` round-trip on the two corrected pose files with `md5sum -c` on restore) differs in
         * exactly `116` rows, ALL of them inside those two poses (`GluteBridgePose` `56`, `PelvicTiltPose` `60`
         * — the two `ELBOW_*` plus each arm's derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain, max
         * `43.0165` u at `GluteBridgePose` `ELBOW_A` `p = 1.0`), with the other `49` classes byte-identical and
         * every pose's stamps
         * unchanged (`maxIkClampAmount` reads identically on both trees — it folds the LEGS' own
         * min-reach clamp — with `boneLengthsVerified = true` and `straightIntentDropped = false`
         * everywhere). B4's own gate is `SupineArmElbowPlaneTest`.
         *
         * **Re-baselined by the B4 trunk correction** (`fix/b4-pelvic-tilt-trunk-ground-plane`, off the
         * B4-elbow merge `7012ccb`): this corpus contains `PelvicTiltPose`, whose rigid trunk is
         * re-authored out of the mat (the tilt's DIRECTION — the pose was carrying
         * `CHEST`/`SHOULDER_A/P`/`NECK_END`/`HEAD_POS` `120/138·sin(0.12)` below a pelvis whose resting
         * layer is `14`). Observed RED on the previous value `-3055606113830305699` before the
         * re-baseline (this live run measured `-4097949659781345677`). Attribution is direct, not
         * inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows,
         * over a `git stash` round-trip on the corrected pose file with `md5sum -c` on restore) differs
         * in exactly `56` rows, ALL of them inside `PelvicTiltPose` (its trunk chain plus the arm joints
         * that hang off the moved shoulder, max `33.0581` u at `HEAD_POS` `p = 1.0`; the pose's legs,
         * pelvis and the world-origin joints are unchanged, and the whole `p = 0.0` sample is
         * byte-identical because the authored tilt is zero at rest), with the other `50` classes
         * byte-identical. The trunk's own gate is `PelvicTiltTrunkPlaneTest`.
         *
         *
         * **Re-baselined by the first reach-band cleanup batch** (`fix/reach-band-batch1-airsquat-squat`,
         * off the C2 merge `d6f4f7f`): this corpus contains `AirSquatPose` and `SquatPose`, whose
         * authored limb targets are now projected onto their own chains' reachable annulus (R2/R4) — the
         * standing-phase ankle was authored as a locked-out leg (`210.288` against the engine's `0.98`
         * extension cap at `205.800`) and the counterbalance reach never left a `9.2 … 15.9` unit radius
         * against the arm chain's `minReach = 40.134`, so the solver relocated the realized
         * end-effector along the authored ray (`4.488 … 30.934` u) and the publishable geometry WAS the
         * projection. Observed RED on the previous value `-4097949659781345677` before the re-baseline (this live run
         * measured `8845305369195660945`). Attribution is direct, not inferred: a whole-corpus dump (`51` classes ×
         * `5` samples × every joint XYZ plus every stamp, the declared limb targets, the supported
         * points and the environment, `255` rows) diffed between this branch and a `git worktree` of
         * `origin/main` @ `d6f4f7f` differs in exactly `152` rows — `76` in each of the two poses, ALL
         * of them limb-chain joints (`KNEE_*` max `0.0495` u, `ANKLE_*`/`HEEL_*`/`TOE_*` `0.0205` u, the
         * derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `<= 0.0056` u, `ELBOW_*`
         * `<= 0.0006` u) — with the other `49` classes byte-identical, every `support` and `env` row
         * byte-identical, and the two poses' reachability stamp dropping from `30.93442 / 30.00000 /
         * 24.24812` to exactly `0.000000` (the relocation this batch removes). The batch's own gate is
         * `SquatReachBandAuthoringTest`; the three pose files stashed on the base tree re-run this guard
         * GREEN on the pre-baseline value (measured in the same session).
          *
          * **Re-baselined by the second reach-band cleanup batch** (`fix/reach-band-batch2-deepsquat-jump-cossack`,
          * off the first reach-band batch's merge `ef9400f`): this corpus is "every production pose class
          * except the classes this correction owns", so it includes the batch's two corrected poses
          * (`DeepSquatHoldPose`, `JumpSquatPose`). Observed RED on the pre-baseline value `8845305369195660945`
          * before the re-baseline (this live run measured `4398860156904083017` below). Attribution is direct, not
          * inferred
          * from this digest: the whole-corpus dump (`51` classes × `14` phases × every `Joint.entries` XYZ ×
          * both frame conditions — `47,124` rows, full float bits) measured on a worktree of `origin/main` @
          * `ef9400f` and on this branch differs in exactly `584` rows — `224` in `DeepSquatHoldPose`, `360` in
          * `JumpSquatPose` — ALL of them limb-chain joints (`KNEE_*` max `0.0484` u, `ANKLE_*`/`HEEL_*`/`TOE_*`
          * `0.0206` u, the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0053` u, `ELBOW_*`
          * `0.0006` u), with the other `49` classes byte-identical — `CossackSquatPose` included, which this
          * batch deliberately does NOT change — and every pelvis/spine/girdle/head joint byte-identical, i.e.
          * the batch is target-side only (a root-side "fix" would have moved the holds' depths). The batch's
          * own gate is `ReachBandBatch2AuthoringTest`.
          *
          * **Re-baselined by the third reach-band cleanup batch**
          * (`fix/reach-band-batch3-halfkneel-hamstring-cobra`, off the #254 merge `cf8a14f`): this corpus is
          * "every production pose class except the classes this correction owns", so it contains
          * `HalfKneelingStretchPose`, `HamstringStretchPose` and `ProneCobraStretchPose` — the batch's poses. Their authored limb targets sat outside their own
          * chains' reachable annulus and are now projected onto it (R2/R4). Observed RED on the pre-baseline value `4398860156904083017` before the re-baseline
          * (this live run measured `-5540195477306562659`). Attribution is direct, not inferred: the whole-corpus A/B (`51`
          * classes × `15` phases × every `Joint.entries` XYZ × both frame conditions — `60,660` rows, full
          * float bits) measured on a worktree of `origin/main` @ `cf8a14f` and on this branch differs in
          * exactly `828` rows — `450` in `HalfKneelingStretchPose`, `180` in `HamstringStretchPose`, `198` in
          * `ProneCobraStretchPose`, ALL of them limb-chain joints (the arm chain's `ELBOW_*` max `0.0356` u,
          * the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0206` u, the tucked leg's
          * `TOE_B`/`HEEL_B`/`ANKLE_B` `0.0059` u and `KNEE_B` `0.0002` u), with the other `48` production
          * classes byte-identical — `CouchStretchPose` included, which shares this site's authoring helper
          * and is deliberately NOT re-scoped — and every pelvis/spine/girdle/head joint of the three
          * byte-identical too, i.e. the batch is target-side only (a root-side "fix" would have moved their
          * stances). The batch's own gate is `ReachBandBatch3AuthoringTest`.
          *
          * **Re-baselined by the fourth (final) reach-band cleanup batch**
          * (`fix/reach-band-batch4-remaining-candidates`, off the #255 merge `ca011ad`): this corpus is "every
          * production pose class except the classes each correction owns", so it contains the batch's five poses
          * (`CouchStretchPose`, `QuadrupedThoracicRotationsPose`, `GluteBridgePose`, `PelvicTiltPose`,
          * `ThoracicExtensionPose`). Their authored limb targets sat outside their own chains' reachable annulus and
          * are now projected onto it (R2/R4). Observed RED on the pre-baseline value `-5540195477306562659` before this re-baseline
          * (the live run measured `3349035099494455806`). Attribution is direct, not inferred: the whole-corpus A/B (`51` classes ×
          * `16` frames — a fresh pipeline's cold first frame plus `15` phases of an advancing pipeline — × every
          * `Joint.entries` XYZ AND every joint rotation, full float bits, `58,464` rows) measured on a worktree of
          * `origin/main` @ `ca011ad` and on this branch differs in exactly `852` rows — `240` in
          * `ThoracicExtensionPose`, `197` in `CouchStretchPose`, `176` in `PelvicTiltPose`, `146` in
          * `GluteBridgePose`, `93` in `QuadrupedThoracicRotationsPose` — i.e. `652` published joint rows, `128`
          * declared-target rows and `72` state rows whose ONLY moving field is the reachability stamp. Every differing
          * joint belongs to the pose's own corrected limb chain (arms: `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/
          * `KNUCKLES_*`/`FINGERTIPS_*`; legs: `KNEE_*`/`ANKLE_*`/`HEEL_*`/`TOE_*`), max published move `0.0324` u
          * (`QuadrupedThoracicRotationsPose`), and the other `46` production classes are byte-identical — every joint
          * ROTATION byte-identical everywhere, every pelvis/spine/girdle/head joint of the five unchanged, and every
          * support set, ground level, environment prop and state flag identical, i.e. the batch is target-side only.
          * The batch's own gate is `ReachBandBatch4AuthoringTest`.
          * **Re-baselined by the canonical-`SkeletonFactory` pose batch**
          * (`fix/canonical-skeletonfactory-pose-batch`, off the #256 merge `4a32d84`): the ten remaining
          * hand-rolled pose trees (`ArmCirclesPose`, `BurpeePose`, `FacePullPose`, `GluteBridgePose`,
          * `HipCarsPose`, `KettlebellSwingPose`, `MountainClimberPose`, `PelvicTiltPose`,
          * `ScapularRetractionPose`, `WallSlidesPose`) adopt `SkeletonFactory.createStandardSkeleton()` (the
          * M11 residue class — the last poses whose five canonical joints published at the world origin), and
          * all ten of them are inside this corpus. Observed RED on the previous value `<3349035099494455806>` before
          * this re-baseline (the live run measured `5608889789699359326`). Attribution is direct, not inferred: the
          * whole-corpus A/B (`51` classes × `16` frames — a fresh pipeline's cold first frame plus `15` advancing
          * phases of a long-lived pipeline — × every `Joint.entries` position AND rotation as FULL FLOAT BITS,
          * plus the published state/stamps, the declared limb targets, the built authoring intents, the support
          * declarations and the environment metadata — `32,403` rows) measured on the pristine base tree
          * (`4a32d84`) and on this tree differs in exactly `800` rows — `80` in each of the ten poses, ALL of
          * them the five canonical joints (`LUMBAR`, `CLAVICLE_A/P`, `SCAPULA_A/P`) × `16` frames, each moving
          * from the world origin to its authored pass-through transform — with every other joint position and
          * rotation, every declared limb target, every stamp, every support set and every environment row
          * byte-identical. The batch's own gate is `CanonicalSkeletonFactoryPoseBatchTest`.
         *
          * **Re-baselined by the animation-coverage phase, batch 1** (`feat/animation-coverage-01`, based on
          * `7df32c0`): this corpus is "every production pose class except its own corrected pose", so the four pose
          * classes the batch ADDED (`HorseStancePose`, `WallSitPose`, `AnkleMobilityPose`, `CalfStretchPose`) entered
          * it. Attribution measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh
          * pipeline per pose) run on the pristine `origin/main` worktree and on this branch reported **all 51
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — on both the
          * `4a32d84` and the rebased `7df32c0` base, so the observed RED was corpus membership, not geometry drift.
          * Pre-rebaseline measurement: `5608889789699359326`.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -7537440469474343391L
    }
}
