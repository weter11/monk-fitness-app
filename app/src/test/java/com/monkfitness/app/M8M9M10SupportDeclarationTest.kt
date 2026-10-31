package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * M8 + M9 + M10 — the support-model declaration pass, asserted on the PUBLISHED frame.
 *
 * The three findings are one defect at the declaration site plus one separate authoring defect inside
 * M8's own pose list:
 *
 * 1. **M9 (8 stretch poses) + M10 (3 core/hip poses) + M8's declaration half (7 upper/dynamic poses)**
 *    — every one of those poses published an **empty** support model. The declaration channel is fully
 *    wired and consumed (`PoseMetadata.support` is resolved by `SupportDefinition.supportPoints`,
 *    injected once per frame by `SkeletonPipeline`, and consumed by
 *    `SkeletonPoseFinalizer.declaredFootSupportPoint` / `declaredHandSupportPoint` /
 *    `supportPlaneNormalFor`); the poses simply never wrote it. So `SkeletonPose.supportedPoints` was
 *    empty on every published frame, the declaration-driven derivation was inert for all 18 poses, and
 *    the contact-surface instruments (`EnvironmentPenetrationTest`'s corpus census) skipped them. No
 *    consumer was defective — the fix is the declaration itself.
 * 2. **M8's second clause — "IK targets never run through `clampTargetToReach` → unreachable authoring
 *    silently solver-clamped"** — is a DIFFERENT root cause, in 5 of those same 7 poses
 *    (`ArmCirclesPose`, `FacePullPose`, `ScapularRetractionPose`, `WallSlidesPose`, `HipCarsPose`).
 *    Each authors its limb IK targets as absolute world points in the **floor-anchored frame the pose
 *    itself used to write**: `targetAnkle = (0, def.foot.ankleHeight, ±z)` and
 *    `handY = standH + def.torsoLength + …`, while `pelvis.localPosition` is `(0,0,0)` and the coarse
 *    root height is written by the ConstraintSolver's STANDING posture pin *after* `build`. At build
 *    time the hip therefore sits at the origin, so the authored ankle target is ~15 units ABOVE it —
 *    inside the chain's minimum reach (leg `minReach` ≈ 56) on the wrong side — and the solver answers
 *    by relocating the effector outward along that upward direction. Measured published frames:
 *    `ANKLE_F` **288.745** against `KNEE_F` **281.859** against `PELVIS/HIP` **235.0** (the legs
 *    realized pointing UP), feet **273 units** above the declared floor, `maxIkClampAmount`
 *    **40.377 … 220.538**, and the arms frozen at maximum reach (an arm circle that should sweep a
 *    253-unit diameter measured a 36.5-unit Y span). No support declaration could be truthful for
 *    geometry like that, so this pass also expresses each limb target relative to the chain root the
 *    pose actually owns, using the engine's own `SkeletonMath.maxReach`, and routes the targets the
 *    chain's minimum flexion still forbids through the engine's own `clampTargetToReach`.
 *
 * Both halves are pose-side only: no engine file, no solver path, no carrier, no API and no new global
 * state.
 *
 * Measured RED on the untouched base tree: 17 declared models missing (the published carrier empty for
 * all of them, on every sampled progress under both frame conditions); `ANKLE_F 288.7450` above
 * `KNEE_F 281.8595` above `HIP_F 235.0000` — the leg hangs UP from its own hip; `maxIkClampAmount`
 * 40.377 / 82.419 / 87.499 / 117.688 / 124.935 (per pose, p=0.5) against the engine's own 0.1
 * reachability flag; the frozen choreography — an arm-circle hand Y span of 36.51 (authored: a
 * 253-unit circle), a wall slide of 0.19, a face pull of 3.92; and
 * `ProneCobraStretchPose FINGERTIPS_A 10.867` against `HAND_A 27.500` (16.6 off),
 * `DynamicWorldsGreatestStretchPose FINGERTIPS_P −12.9`.
 *
 * The counterfactual removes the declaration from a corrected pose through a delegating twin
 * (`PoseBuilder by …`, the pattern `EnvironmentPenetrationTest`/`SupportDeclarationChannelTest` use):
 * the published carrier collapses to ∅ and the foot's long axis reverts to the un-derived orientation,
 * i.e. the production assertions above are driven by the declaration.
 */
class M8M9M10SupportDeclarationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The engine's penetration band (unchanged, same value the corpus invariant uses). */
    private val penetrationBand = 2.0f

    /** The engine's own reachability flag threshold (`ExerciseValidator`: `maxIkClampAmount > 0.1`). */
    private val reachFlagThreshold = 0.1f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** A frame captured BY VALUE — the pipeline publishes a reused buffer (the T-7 trap). */
    private fun snapshot(f: SkeletonPose) = SkeletonPose().apply { copyFrom(f) }

    private fun coldFrame(name: String, p: Float): SkeletonPose =
        snapshot(SkeletonPipeline(def).produceFrame(MotionProbe.build(name), ctx(p)).pose)

    /** One builder + one pipeline advancing 0 → 1, every frame captured by value. */
    private fun playingFrames(name: String): List<SkeletonPose> {
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)
        return samples.map { snapshot(pipeline.produceFrame(builder, ctx(it)).pose) }
    }



    private fun p(name: String) = SupportPoint.valueOf(name)

    private val declared: Map<String, Set<SupportPoint>> = mapOf(
        // M8 — the 7 upper/dynamic poses
        "ArmCirclesPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "BurpeePose" to setOf(p("LEFT_HAND"), p("RIGHT_HAND")),
        "FacePullPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "HipCarsPose" to setOf(p("RIGHT_FOOT")),
        "KettlebellSwingPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "ScapularRetractionPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "WallSlidesPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        // M9 — the stretch family (HamstringStretchPose is the measured vocabulary exception)
        "CouchStretchPose" to setOf(p("LEFT_FOOT")),
        "DynamicWorldsGreatestStretchPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT"), p("RIGHT_HAND")),
        "HalfKneelingStretchPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "LatStretchPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "ProneCobraStretchPose" to setOf(p("LEFT_HAND"), p("RIGHT_HAND"), p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "ReverseSnowAngelPose" to setOf(p("LEFT_HAND"), p("RIGHT_HAND"), p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "SupermanPose" to setOf(p("LEFT_HAND"), p("RIGHT_HAND")),
        // M10 — the core/hip poses
        "GluteBridgePose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "PelvicTiltPose" to setOf(p("LEFT_FOOT"), p("RIGHT_FOOT")),
        "MountainClimberPose" to setOf(p("LEFT_HAND"), p("RIGHT_HAND"))
    )

    /**
     * The five poses whose authored limb targets were re-expressed in the chain-root frame (M8's
     * second clause) and the authored motion each one must keep (floors from the measured
     * post-correction spans; the pre-fix frozen values are quoted at each entry).
     */
    private val correctedStanding = mapOf(
        "ArmCirclesPose" to 250f,        // pre-fix hand Y span 36.51 (authored circle 2 × r = 253)
        "FacePullPose" to 50f,           // pre-fix 3.92
        "ScapularRetractionPose" to 19f, // pre-fix 0.72 (the elbow's authored squeeze travel)
        "WallSlidesPose" to 80f,         // pre-fix 0.19 (the authored slide: −10 → +60 from the shoulder)
        "HipCarsPose" to 29f             // pre-fix 5.42 on the ankle / 74.73 on the knee (the clamp artifact)
    )

    /** The declared contacts whose chain must end up IN its declared plane (the derivation's job). */
    private val declaredPlaneContacts: List<Pair<String, String>> = listOf(
        "ProneCobraStretchPose" to "HAND_A",
        "ProneCobraStretchPose" to "HAND_P",
        "ReverseSnowAngelPose" to "HAND_A",
        "DynamicWorldsGreatestStretchPose" to "HAND_P",
        "SupermanPose" to "HAND_A"
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)



    @Test
    fun everyPoseOfTheThreeFamiliesDeclaresItsSupportModelOnTheOneCanonicalChannel() {
        val missing = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        for ((name, expected) in declared) {
            val builder = MotionProbe.build(name)
            val actual = builder.metadata.support.contacts.map { it.point }.toSet()
            if (actual.isEmpty()) missing.add(name)
            else if (actual != expected) mismatched.add("$name declared=$actual expected=$expected")
        }
        assertTrue(
            "every pose of the M8/M9/M10 group must state its support model on the one canonical " +
                "channel (`metadata.support`, B-2 — the channel the engine actually reads): " + missing,
            missing.isEmpty()
        )
        assertTrue("the declared model must be the intended one:\n" + mismatched.joinToString("\n"), mismatched.isEmpty())
        assertTrue("anti-vacuity: the group must be enumerated in full (found ${declared.size})", declared.size == 17)
        // The pass's declared-vocabulary guard: no pose may declare a point the canonical map
        // cannot resolve (a declaration no consumer can use is the defect this pass removes).
        val unresolved = declared.flatMap { (name, points) ->
            points.filter { SupportMath.jointsFor(it).isEmpty() }.map { "$name declares $it" }
        }
        assertTrue("every declared point must resolve through the canonical map: $unresolved", unresolved.isEmpty())
    }



    @Test
    fun theDeclaredSupportModelReachesThePublishedCarrier() {
        val mismatches = mutableListOf<String>()
        for ((name, expected) in declared) {
            for (p in samples) {
                val cold = coldFrame(name, p).supportedPoints.toSet()
                if (cold != expected) mismatches.add("$name COLD p=$p published=$cold declared=$expected")
            }
            val playing = playingFrames(name)
            playing.forEachIndexed { i, frame ->
                val published = frame.supportedPoints.toSet()
                if (published != expected) {
                    mismatches.add("$name PLAYING p=${samples[i]} published=$published declared=$expected")
                }
            }
        }
        assertTrue(
            "the declared support model must be the model the published frame carries (the pipeline's " +
                "single R8 injection):\n" + mismatches.joinToString("\n"), mismatches.isEmpty()
        )
        // Anti-vacuity: the production corpus must contain the group (a renamed/removed class would
        // otherwise shrink the observation set silently).
        val corpus = File(moduleRoot(), "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
        val missingFromCorpus = declared.keys.filterNot { corpus.contains(it) }
        assertTrue("the group's classes must exist in the production corpus: $missingFromCorpus", missingFromCorpus.isEmpty())
    }



    @Test
    fun theCorrectedFamilyRealizesItsLegsDownToTheDeclaredSupport() {
        val failures = mutableListOf<String>()
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        for (name in correctedStanding.keys) {
            // Only the legs whose foot the pose DECLARES are asserted: the declaration is the
            // subject here, and a pose may legitimately lift the other foot (HipCars' circling leg).
            val declaredPoints = declared.getValue(name)
            val chains = listOf(
                Triple(p("LEFT_FOOT"), p("LEFT_TOES"), listOf(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)),
                Triple(p("RIGHT_FOOT"), p("RIGHT_TOES"), listOf(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B))
            ).filter { (foot, toes, _) -> declaredPoints.contains(foot) || declaredPoints.contains(toes) }
            for (frame in playingFrames(name) + samples.map { coldFrame(name, it) }) {
                for ((_, _, chain) in chains) {
                    val (hip, knee, ankle, heel, toe) = chain
                    val hipY = frame.getJoint(hip).y
                    val kneeY = frame.getJoint(knee).y
                    val ankleY = frame.getJoint(ankle).y
                    val span = hipY - ankleY
                    // The leg hangs from its own hip, and it hangs a full reachable span: the foot
                    // reaches the floor line the pose's own standing frame declares.
                    if (!(ankleY < kneeY && kneeY < hipY)) {
                        failures.add("$name: $ankle (${f(ankleY)}) is not below $knee (${f(kneeY)}) below $hip (${f(hipY)})")
                    }
                    if (abs(span - legSpan) > 1f) {
                        failures.add("$name: $hip→$ankle span ${f(span)} != the chain's reachable span ${f(legSpan)}")
                    }
                    // A declared foot rests IN its plane: the contact joints are level with the ankle.
                    for (joint in listOf(heel, toe)) {
                        val dy = abs(frame.getJoint(joint).y - ankleY)
                        if (dy > penetrationBand) {
                            failures.add("$name: declared foot joint $joint is ${f(dy)} off the ankle's plane")
                        }
                    }
                }
            }
        }
        assertTrue(
            "a pose that declares its feet on the ground must realize its legs DOWN to that ground " +
                "(measured pre-fix: ANKLE_F 288.7450 ABOVE KNEE_F 281.8595 ABOVE HIP_F 235.0000):\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun theCorrectedFamilyDeclaresRealizableLimbTargets() {
        val clamped = mutableListOf<String>()
        for (name in correctedStanding.keys) {
            for (p in samples) {
                val frame = coldFrame(name, p)
                if (frame.maxIkClampAmount > reachFlagThreshold) {
                    clamped.add("$name COLD p=$p maxIkClampAmount=${f(frame.maxIkClampAmount)}")
                }
            }
            val builder = MotionProbe.build(name)
            val pipeline = SkeletonPipeline(def)
            samples.forEach { p ->
                val frame = pipeline.produceFrame(builder, ctx(p)).pose
                if (frame.maxIkClampAmount > reachFlagThreshold) {
                    clamped.add("$name PLAYING p=$p maxIkClampAmount=${f(frame.maxIkClampAmount)}")
                }
            }
        }
        assertTrue(
            "the authored limb targets must be realizable as declared: the engine's own reachability " +
                "reading (`maxIkClampAmount`, flagged by `ExerciseValidator` above $reachFlagThreshold) must " +
                "stay clear. Measured pre-fix: 40.377 / 82.419 / 87.499 / 117.688 / 124.935:\n" +
                clamped.joinToString("\n"),
            clamped.isEmpty()
        )
    }

    @Test
    fun theCorrectedFamilyKeepsItsAuthoredMotion() {
        val frozen = mutableListOf<String>()
        for ((name, floor) in correctedStanding) {
            val travel = MotionProbe.maxTravel3D(MotionProbe.build(name))
            if (travel < floor) frozen.add("$name travel=${f(travel)} < the authored floor ${f(floor)}")
        }
        assertTrue(
            "the corrected family must animate the choreography its poses author — a return to the " +
                "pre-fix frozen limbs (measured: arm circles 36.51, wall slide 0.19, face pull 3.92, " +
                "scapular squeeze 0.72, hip-car ankle 5.42) fails here:\n" + frozen.joinToString("\n"),
            frozen.isEmpty()
        )
    }



    @Test
    fun aDeclaredHandContactLiesInItsDeclaredPlane() {
        val off = mutableListOf<String>()
        for ((name, wrist) in declaredPlaneContacts) {
            val wristJoint = Joint.valueOf(wrist)
            val chain = listOf(
                Joint.valueOf(wrist.replace("HAND", "PALM")),
                Joint.valueOf(wrist.replace("HAND", "KNUCKLES")),
                Joint.valueOf(wrist.replace("HAND", "FINGERTIPS"))
            )
            for (p in samples) {
                val frame = coldFrame(name, p)
                val wristY = frame.getJoint(wristJoint).y
                for (joint in chain) {
                    val dy = abs(frame.getJoint(joint).y - wristY)
                    if (dy > penetrationBand) {
                        off.add("$name p=$p $joint is ${f(dy)} off its declared plane ($wrist Y=${f(wristY)})")
                    }
                }
            }
        }
        assertTrue(
            "a declared hand contact must be realized IN the plane its declaration names (the " +
                "declaration-driven derivation, i.e. the contact reaching its consumer). Measured " +
                "pre-fix: ProneCobraStretchPose FINGERTIPS_A 10.867 vs HAND_A 27.500;\n" +
                "DynamicWorldsGreatestStretchPose FINGERTIPS_P −12.900:\n" + off.joinToString("\n"),
            off.isEmpty()
        )
    }



    /**
     * The production prone cobra with its support declaration deliberately removed (B-2's defect
     * class). It delegates `build()` to the production pose and varies ONLY the declaration, so the
     * observation below isolates exactly what the declaration drives.
     */
    private class DeclarationRemovedCobra : PoseBuilder by com.monkfitness.app.poses.ProneCobraStretchPose() {
        override val metadata = com.monkfitness.app.poses.ProneCobraStretchPose().metadata.copy(
            support = SupportDefinition(pivot = PivotType.FEET, contacts = emptySet())
        )
    }

    @Test
    fun removingTheDeclarationEmptiesThePublishedSupportModelAndItsDerivation() {
        val broken = DeclarationRemovedCobra()
        assertEquals(
            "the twin declares no support",
            emptySet<SupportPoint>(), broken.metadata.support.contacts.map { it.point }.toSet()
        )
        for (p in samples) {
            val published = SkeletonPipeline(def).produceFrame(broken, ctx(p)).pose.supportedPoints.toSet()
            assertEquals("with no declaration the published model must be empty at p=$p", emptySet<SupportPoint>(), published)
        }

        // …and the derivation that consumes it is driven by the declaration: the production pose's
        // declared hand lies flat in the surface its declaration names, while the twin — same build,
        // no declaration — realizes the same hand back through that surface. This is the pass's RED
        // reading reproduced from the tree itself (pre-fix `FINGERTIPS_A` −6.990 at the seam).
        val production = coldFrame("ProneCobraStretchPose", 0.0f)
        val undeclared = snapshot(SkeletonPipeline(def).produceFrame(broken, ctx(0.0f)).pose)
        val flat = production.getJoint(Joint.FINGERTIPS_A).y
        val through = undeclared.getJoint(Joint.FINGERTIPS_A).y
        assertTrue(
            "the production pose's declared hand contact must be published and realized in its plane " +
                "(fingertips ${f(flat)} vs wrist ${f(production.getJoint(Joint.HAND_A).y)})",
            production.supportedPoints.isNotEmpty() && abs(flat - production.getJoint(Joint.HAND_A).y) <= penetrationBand
        )
        assertTrue(
            "removing the declaration must put the declared contact back through its own surface " +
                "(the derivation is driven by the declared model, not vacuous) — measured " +
                "fingertips ${f(through)} vs ${f(flat)}, delta ${f(flat - through)}",
            flat - through > 1f && through < 0f
        )
    }



    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside the M8/M9/M10 group must be byte-identical to the " +
                "pre-fix tree (the digest covers every joint of every sampled frame of every other " +
                "production pose class); a change here means the pass leaked outside its scope. " +
                "measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** The app module root, located by walking up from the test JVM's working directory. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }

    private fun corpusDigest(): Long {
        val names = File(moduleRoot(), "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || declared.containsKey(it) }
            .sorted()
        assertTrue("anti-vacuity: the digest corpus must contain the untouched poses (found ${names.size})", names.size >= 30)
        assertTrue("anti-vacuity: the correction scope must not swallow the corpus", declared.size == 17)

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
         * Digest of the production pose classes OUTSIDE the M8/M9/M10 group — the blast-radius guard.
         * Measured **equal on the pre-fix tree and on the corrected tree** (with the 17 corrected classes
         * excluded), i.e. the pass is confined to its own finding set; the guard's own mutation check is
         * the observed RED when a corrected class is folded back into the corpus.
         *
         * Note `HamstringStretchPose` stays INSIDE this corpus: it is deliberately left undeclared (the
         * foot-vocabulary gap recorded in the M9 entry), so any later correction to it moves this value.
         *
         * Corpus-sensitive by construction: adding a pose class, or moving any pose inside the corpus,
         * moves the digest. A RED here is either membership or a leak outside the group — attribute it
         * from a whole-corpus dump diffed against a pristine `origin/main` worktree, never by pasting the
         * new number in.
         */
        const val UNAFFECTED_CORPUS_DIGEST = 2368716596148803243L
    }
}
