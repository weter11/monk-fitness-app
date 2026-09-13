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
 * **M8 + M9 + M10 — the support-model declaration pass, asserted on the PUBLISHED frame.**
 *
 * ## What was wrong (measured on `origin/main` @ `fc65695` before this change, and re-measured on
 * the M6/M7 merge `eea705c` after this pass was rebased onto it)
 *
 * The three findings in `docs/STABILIZATION_AUDIT.md` §3 are one defect at the declaration site and
 * one separate authoring defect inside M8's own pose list:
 *
 * 1. **M9 (8 stretch poses) + M10 (3 core/hip poses) + M8's declaration half (7 upper/dynamic
 *    poses)** — every one of those poses published an **empty** support model. The engine's
 *    declaration channel is fully wired and consumed (`PoseMetadata.support` — the ONE channel since
 *    B-2 — is resolved by `SupportDefinition.supportPoints`, injected once per frame by
 *    `SkeletonPipeline` (R8), and consumed by `SkeletonPoseFinalizer.declaredFootSupportPoint` /
 *    `declaredHandSupportPoint` / `supportPlaneNormalFor`); the poses simply never wrote it. So the
 *    engine's own statement of where the body touches the world was absent, `SkeletonPose.
 *    supportedPoints` was empty on every published frame, the declaration-driven derivation was
 *    inert for all 18 poses, and the contact-surface instruments (the `EnvironmentPenetrationTest`
 *    corpus census) skipped them.
 *    *This is the same root cause for all three findings: a contact-bearing pose that never declares.
 *    No consumer was defective — the fix is the declaration itself.*
 * 2. **M8's second clause — "IK targets never run through `clampTargetToReach` → unreachable
 *    authoring silently solver-clamped"** — is a DIFFERENT root cause, in 5 of those same 7 poses
 *    (`ArmCirclesPose`, `FacePullPose`, `ScapularRetractionPose`, `WallSlidesPose`, `HipCarsPose`).
 *    Each authors its limb IK targets as absolute world points in the **floor-anchored frame the
 *    pose itself used to write**: `targetAnkle = (0, def.foot.ankleHeight, ±z)` and
 *    `handY = standH + def.torsoLength + …`, while `pelvis.localPosition` is `(0,0,0)` and the coarse
 *    root height is written by the ConstraintSolver's STANDING posture pin *after* `build` (B3). At
 *    build time the hip therefore sits at the origin, so the authored ankle target is ~15 units
 *    ABOVE it — inside the chain's minimum reach (leg `minReach` ≈ 56) on the wrong side — and the
 *    solver answers by relocating the effector outward along that upward direction. Measured
 *    published frames: `ANKLE_F` **288.745** against `KNEE_F` **281.859** against `PELVIS/HIP`
 *    **235.0** (the legs realized pointing UP), feet **273 units** above the declared floor,
 *    `maxIkClampAmount` **40.377 … 220.538**, and the arms frozen at maximum reach (an arm circle
 *    that should sweep a 253-unit diameter measured a 36.5-unit Y span). No support declaration
 *    could be truthful for geometry like that, which is why this pass corrects the authoring frame
 *    too — the smallest shared correction: express each limb target relative to the chain root the
 *    pose actually owns, using the engine's own `SkeletonMath.maxReach`, and route the targets that
 *    the chain's minimum flexion still forbids through the engine's own `clampTargetToReach`
 *    (the R2 reach-target helper the rest of the corpus authors with).
 *
 * Both halves are pose-side only: no engine file, no solver path, no carrier, no API and no new
 * global state.
 *
 * ## RED evidence (this class on the untouched base tree)
 *
 * * `everyPoseOfTheThreeFamiliesDeclaresItsSupportModelOnTheOneCanonicalChannel` — 17 declared
 *   models missing (the published carrier is empty for all of them).
 * * `theDeclaredSupportModelReachesThePublishedCarrier` — the same 17, on every sampled progress
 *   under both frame conditions.
 * * `theCorrectedFamilyRealizesItsLegsDownToTheDeclaredSupport` — `ANKLE_F 288.7450` above
 *   `KNEE_F 281.8595` above `HIP_F 235.0000`: the leg hangs UP from its own hip.
 * * `theCorrectedFamilyDeclaresRealizableLimbTargets` — `maxIkClampAmount` 40.377 / 82.419 /
 *   87.499 / 117.688 / 124.935 (per pose, p=0.5) against the engine's own 0.1 reachability flag.
 * * `theCorrectedFamilyKeepsItsAuthoredMotion` — the frozen choreography: an arm-circle hand Y span
 *   of 36.51 (authored: a 253-unit circle), a wall slide of 0.19, a face pull of 3.92.
 * * `aDeclaredHandContactLiesInItsDeclaredPlane` — `ProneCobraStretchPose FINGERTIPS_A 10.867`
 *   against `HAND_A 27.500` (16.6 off), `DynamicWorldsGreatestStretchPose FINGERTIPS_P −12.9`.
 *
 * ## Counterfactual (the essential-fix removal)
 *
 * * `removingTheDeclarationEmptiesThePublishedSupportModelAndItsDerivation` removes the declaration
 *   from a corrected pose through a delegating twin (`PoseBuilder by …`, the pattern
 *   `EnvironmentPenetrationTest`/`SupportDeclarationChannelTest` use): the published carrier
 *   collapses to ∅ and the foot's long axis reverts to the un-derived orientation, i.e. the
 *   production assertions above are driven by the declaration.
 * * Cross-tree: this class itself is RED on pristine `origin/main` — both on `fc65695` and on the
 *   merged base `eea705c` (`eea705c` re-run: 7 of 8 FAILED, the same seven) — it is the same
 *   class, the same pipeline, no test-side tolerance; see the finding record for the run.
 *
 * ## Blast radius
 *
 * `unaffectedPosesPublishByteIdenticalGeometry` pins a digest over every production pose class
 * OUTSIDE the 18 this pass touches, from the M1/M3M5/Plank sibling guards' own recipe.
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

    // ------------------------------------------------------------------------------------------
    // The group's declaration, exactly as the poses state it on the one canonical channel
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 1. The declaration exists, on the one canonical channel
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 2. The declaration reaches the consumer: the published carrier
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 3. The corrected family: the declared support is where the body actually is
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 4. The declared hand contacts lie IN the surface their declaration names
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 5. Counterfactual: remove the declaration, the published observation collapses
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 6. Blast radius: everything outside the 18 corrected classes is byte-identical
    // ------------------------------------------------------------------------------------------

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
         * Measured **equal on the pre-fix tree and on the corrected tree** (with the 17 corrected
         * classes excluded), i.e. the pass is confined to its own finding set. Captured on
         * `origin/main` @ `fc65695` (pre-fix), re-verified on the merged base `eea705c` and after the
         * correction (the corpus excludes the two poses the M6/M7 merge re-authored, so it is equal on
         * all three trees); the guard's own
         * mutation check is the observed RED when a corrected class is folded back into the corpus.
         *
         * **Re-baselined by the M13 hamstring forward-reach correction** (`fix/m13-hamstring-reach`,
         * off `0301563`): this corpus is "every production pose the M8/M9/M10 pass did NOT declare", so it still contains `HamstringStretchPose` (that pose is deliberately left undeclared — the foot-vocabulary gap recorded in the M9 entry), so it includes `HamstringStretchPose`, the one class that
         * correction owns. Observed RED on the pre-fix value `-9118394861084468944` before the re-baseline (this
         * live run measured `-3670557964446835822` below); the five scope digests were re-run with the pose file
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
         * pose class outside the M8/M9/M10 group", so it contains `LatStretchPose` (M11 — the canonical
         * authored hierarchy replaces the hand-rolled tree, publishing `LUMBAR`/`CLAVICLE_*`/`SCAPULA_*`
         * instead of the world origin) and `CatCowPose` (M12 — the four-point support declaration and the
         * reachable-by-construction leg targets). Observed RED on the previous value before this
         * re-baseline. Attribution is direct, not inferred: the whole-corpus dump (`50` classes × `5`
         * samples × every joint, `8415` rows, `git stash` round-trip on the two pose files) differs in
         * exactly `95` xyz rows — `70` in `CatCowPose`, `25` in `LatStretchPose` — and the other `48`
         * classes are byte-identical. That pass's own blast-radius guard is
         * `M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST`.
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
        * this correction removes in the same change). Observed RED on the previous value `8463731255735644640` before
        * the re-baseline (this live run measured `9137988112138109314`). Attribution is direct, not
        * inferred
        * whole-corpus dump (`51` classes x `5` samples x every joint XYZ, `8415` rows, a `git stash`
        * round-trip on the corrected pose file with `md5sum -c` on restore) differs in exactly `60` rows,
        * ALL of them inside `DiamondPushUpPose` — `ELBOW_A`/`ELBOW_P` at all five samples
        * (`19.96 ... 46.79` u) plus the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` pair
        * (<= `8e-6` u float drift at four samples, and `5.29` / `10.57` / `19.38` u at `p = 0.5`, where
        * the engine's planted-hand flattening now fires because the elbow is above the hand) — with the
        * other `50` classes byte-identical. B1's own regression is `DiamondPushUpElbowClearanceTest`.
         *
         * **Unchanged by the B1 integration / B2 merge**: this corpus excludes the declaration group both
         * corrected poses belong to (`DiamondPushUpPose` declares hands + toes, `DynamicWorldsGreatestStretchPose`
         * feet + hand), so the constant stands and was re-verified equal on the merged tree.
         *
         * **Re-baselined by the B3 side-plank support-leg correction** (`fix/b3-sideplank-knee-plane`,
         * rebased onto the B2 merge `f8f8b24`, with B1 integrated): this corpus contains
         * `IsometricSidePlankPose`, the pose B3 corrects — the support leg's residual knee bend is
         * authored out of the mat now (the bend plane's pole `(0, -1, 0)` → `(0, 1, 0)`), so the pose
         * publishes `KNEE_B` above its own declared plane instead of `25.8897` below it. Observed RED on
         * the B1-integrated value `9137988112138109314` before this re-baseline (this live run measured `-7807207721990292460`).
         * Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5` samples × every
         * joint XYZ, `8415` rows, over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs from the pre-B3 tree in exactly `5` xyz rows — all `5` samples
         * of `IsometricSidePlankPose`'s `KNEE_B` — while B1's `60` `DiamondPushUpPose` rows and B2's `20`
         * `DynamicWorldsGreatestStretchPose` rows are untouched by this pass. B3's own gate is
         * `IsometricSidePlankKneePlaneTest`.
         *
         * **Re-baselined by the first reach-band cleanup batch** (`fix/reach-band-batch1-airsquat-squat`,
         * off the C2 merge `d6f4f7f`): this corpus contains `AirSquatPose` and `SquatPose`, whose
         * authored limb targets are now projected onto their own chains' reachable annulus (R2/R4) — the
         * standing-phase ankle was authored as a locked-out leg (`210.288` against the engine's `0.98`
         * extension cap at `205.800`) and the counterbalance reach never left a `9.2 … 15.9` unit radius
         * against the arm chain's `minReach = 40.134`, so the solver relocated the realized
         * end-effector along the authored ray (`4.488 … 30.934` u) and the publishable geometry WAS the
         * projection. Observed RED on the previous value `-7807207721990292460` before the re-baseline (this live run
         * measured `-8876365443930750926`). Attribution is direct, not inferred: a whole-corpus dump (`51` classes ×
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
          * (`DeepSquatHoldPose`, `JumpSquatPose`). Observed RED on the pre-baseline value `-8876365443930750926`
          * before the re-baseline (this live run measured `789863381076342762` below). Attribution is direct, not
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
          * `HamstringStretchPose` — the batch's poses. Their authored limb targets sat outside their own
          * chains' reachable annulus and are now projected onto it (R2/R4). Observed RED on the pre-baseline value `789863381076342762` before the re-baseline
          * (this live run measured `1604810780889823966`). Attribution is direct, not inferred: the whole-corpus A/B (`51`
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
          * are now projected onto it (R2/R4). Observed RED on the pre-baseline value `1604810780889823966` before this re-baseline
          * (the live run measured `7499664576150638435`). Attribution is direct, not inferred: the whole-corpus A/B (`51` classes ×
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
         */
        const val UNAFFECTED_CORPUS_DIGEST = 7499664576150638435L
    }
}
