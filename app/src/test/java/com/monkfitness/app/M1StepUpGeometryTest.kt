package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **M1 — the Step-Up's support foot stands on the step the pose declares.**
 *
 * Engine contract (`docs/ARCHITECTURE_V2.md` §4.1 support declaration, `docs/BIOMECHANICS.md`): every
 * body point a pose declares as resting on the environment must actually rest on it, and the
 * environment the pose declares (`metadata.environment`, `metadata.support`) is the engine's own
 * statement of WHAT supports the body and WHERE. `StepUpPose` declares a `StepProp` (top plane
 * `y = 36`, footprint `X ∈ [−10, 34] × Z ∈ [−22, 22]` on the recorded baseline) and declares both feet
 * (`LEFT_FOOT`, `RIGHT_FOOT`) as support contacts.
 *
 * ## The defect this file gates (P11 whole-system audit, recorded as `docs/STABILIZATION_AUDIT.md` §3 M1)
 *
 * > *"Lead/trail feet at Z=∓25.3 but step prop spans only Z∈[−22,+22]; both feet overhang the step."*
 *
 * Re-measured on the current tree through the production pipeline (`produceFrame(pose, ctx)`, the
 * metadata-derived entry point playback uses), sampling `progress` 0 → 1 — the audit's Z measurement
 * is exact, and the produced frame shows what it implies:
 *
 * | frame | `LEFT_FOOT` joints | its resolved surface | pelvis |
 * |---|---|---|---|
 * | `p = 0` (floor) | `(12, 25, −25.3)` ankle | ground `0` | `228.0` |
 * | `p = 0.5` (top) | `(12, 36, −25.3)` ankle | **ground `0`** | `241.0` |
 *
 * i.e. the foot that the pose puts "on the step" is at the step's own top plane while lying *outside*
 * the step's footprint in `Z`, so the engine's support rule (`supportPlaneNormalFor`: a contact's
 * surface is a box/step/bench **top** only when the contact's canonical joint centroid is inside its
 * footprint, otherwise the ground) resolves **both** feet to the ground at every frame: the declared
 * step never supports anything, and the "ascent" is 11 units of hover beside it (`pelvis rise 13.0`
 * including the breath, against a 36-unit step) — a leg raise beside a step, not a
 * step-up. Neither foot is ever on the tread: the `X` extent overhangs the tread's front edge too
 * (toe `36.85` against a tread ending at `34`).
 *
 * The same measurement exposes the second half of the same authoring error: a foot standing on a
 * surface rides the foot's own contact radius above it — the pose's own floor relationship
 * (`footRestY = 25`), the engine's contact-radius convention (`PushUpPlank.ankleHeight =
 * BASE_ANKLE_HEIGHT + supportElevation`) and the measured relationship on the one other
 * foot-on-a-prop production pose, `DeclinePushUpPose` (a 40-unit box, foot contacts at `65.0`). The
 * pre-fix pose instead uses the step's **surface** height as the feet's **joint** height, so a foot
 * "on" the step would in fact be buried to its ankle inside it.
 *
 * ## The corrected invariant (what this file asserts)
 *
 *  1. [thePlantedFootRestsOnTheDeclaredStepAndNotOnTheGround] — at the top of the rep the support
 *     foot's resolved surface is the step's top, not the ground (the audit's symptom, in the engine's
 *     own terms), and at the seam it is the ground.
 *  2. [theWholePlantedFootIsOnTheTread] — every joint of the declared `LEFT_FOOT` contact lies inside
 *     the declared tread footprint, above its top plane: no part of the foot overhangs the step.
 *  3. [theFootKeepsItsContactRadiusWhenItStandsOnTheStep] — the planted foot's height above the
 *     step's top equals the same pose's floor-rest height above the ground (band = the engine's
 *     unchanged 2 units), i.e. the foot stands ON the step instead of through it.
 *  4. [theAscentBuysTheStepsHeight] — the pelvis travels the step's declared height, not a fraction
 *     of it.
 *  5. [theTrailingFootStaysOnTheFloorSideOfTheStep] — the trailing foot is never resolved to the step
 *     and never penetrates the ground, and is lifted clear of the floor at the top (BPS §7/§8: in
 *     this variant the trailing foot stays on the floor's side and does not stand on the step).
 *  6. [noSampledFramePenetratesItsResolvedSurfaceAndTheCarriersStayHonest] — the invariant for the
 *     whole rep, both frame conditions, plus `maxIkClampAmount == 0` / `boneLengthsVerified` (no
 *     solver clamping hidden inside the geometry).
 *  7. [sampledFramesArePublishedDistinctSnapshots] — anti-vacuity: the frames measured are distinct
 *     published snapshots that really move, and the published frame carries the declared support model.
 *  8. [theSupportFootIsFixedOnceItIsPlantedAndTheLoopSeamIsClosed] — the planted foot does not slide
 *     once it is on the step, and the `PING_PONG` seam is closed.
 *  9. [unaffectedPosesPublishByteIdenticalGeometry] — M1's blast radius: the OTHER 50 production pose
 *     classes publish byte-identical geometry (digest captured on the pre-fix tree).
 *
 * ## Why the alternative fix is not this one (measured)
 *
 * Widening the declared step so its footprint covers the athlete's *floor* stance (the literal reading
 * of the audit's `Z` measurement — a `Z` run of `±25.3` or wider) is refuted by measurement: the
 * planted foot's rest pose would then lie inside the widened footprint while it is on the floor, so
 * the engine resolves its surface to the step's **top** and the foot's contact joints sit `−11` below
 * the surface it is declared to rest on — a penetration [6] and [2] both reject, and a foot physically
 * inside the step's solid volume. The support foot has to be placed ON the tread instead (that is what
 * "steps onto the box" means), which is what the corrected pose does; the trailing foot is held on the
 * floor's side of the step's near edge, per BPS §8.
 */
class M1StepUpGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val poseName = "StepUpPose"

    /** Rep samples: the seam (both feet on the floor), the ascent, and the top of the rep. */
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The frames at which the lead foot is at the step's plan (placed, load-bearing). */
    private val plantedSamples = listOf(0.25f, 0.5f, 0.75f)

    /** The engine's penetration band (unchanged since B-6): a declared contact joint may not sit more
     *  than this far below the surface its own declaration rests on. */
    private val band = 2.0f

    /** Slack for the ascent's own breath micro-driver (zero at the rep endpoints). */
    private val breathBudget = 4.0f

    private enum class FrameCondition { COLD, PLAYING }

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    /** A frame captured BY VALUE: the pipeline publishes the Finalizer's reused output buffer. */
    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /**
     * All sampled frames of the pose under both frame conditions, captured by value, through the
     * production entry point playback uses (`produceFrame(pose, ctx)`, which derives the Frame Context
     * from `metadata` — the declaration this file asserts against).
     */
    private fun frames(): List<Triple<FrameCondition, Float, SkeletonPose>> {
        val out = mutableListOf<Triple<FrameCondition, Float, SkeletonPose>>()
        // COLD — the genuinely cold first frame: a fresh pose instance on a fresh pipeline.
        for (p in samples) {
            val cold = SkeletonPipeline(def).produceFrame(MotionProbe.build(poseName), ctx(p)).pose
            out.add(Triple(FrameCondition.COLD, p, snapshot(cold)))
        }
        // PLAYING — one builder + one pipeline advancing 0 -> 1, frames captured by value.
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(poseName)
        for (p in samples) {
            val frame = pipeline.produceFrame(builder, ctx(p)).pose
            out.add(Triple(FrameCondition.PLAYING, p, snapshot(frame)))
        }
        return out
    }

    private fun frameAt(condition: FrameCondition, progress: Float): SkeletonPose =
        frames().first { it.first == condition && it.second == progress }.third

    private fun builder() = MotionProbe.build(poseName)

    // ---------------------------------------------------------------------------------------------
    // The declared environment — read from the pose's own metadata, never re-typed here
    // ---------------------------------------------------------------------------------------------

    /** The declared box-like prop's footprint + top plane, from the pose's own declaration. */
    private class Box(val x0: Float, val x1: Float, val z0: Float, val z1: Float, val top: Float)

    private fun declaredStep(): Box {
        val props = builder().metadata.environment.props
        assertEquals("the pose must declare exactly the step it steps onto", 1, props.size)
        val prop = props.single()
        return when (prop) {
            is BoxProp -> Box(prop.center.x - prop.width / 2, prop.center.x + prop.width / 2, prop.center.z - prop.depth / 2, prop.center.z + prop.depth / 2, prop.center.y + prop.height / 2)
            is StepProp -> Box(prop.center.x - prop.width / 2, prop.center.x + prop.width / 2, prop.center.z - prop.depth / 2, prop.center.z + prop.depth / 2, prop.center.y + prop.height / 2)
            is BenchProp -> Box(prop.center.x - prop.width / 2, prop.center.x + prop.width / 2, prop.center.z - prop.depth / 2, prop.center.z + prop.depth / 2, prop.center.y + prop.height / 2)
            is WallProp -> Box(prop.center.x - prop.width / 2, prop.center.x + prop.width / 2, prop.center.z - prop.depth / 2, prop.center.z + prop.depth / 2, prop.center.y + prop.height / 2)
        }
    }

    private fun declaredGround(): Float = builder().metadata.environment.ground.level

    /**
     * The surface a declared contact rests on, by the engine's own rule
     * (`SkeletonPoseFinalizer.supportPlaneNormalFor`, mirrored here because the engine's helper is
     * private — the same mirror the B-6/B-7 support tests use): ONE plane per contact, resolved from
     * the contact's canonical joint centroid — a box/step/bench **top** when that centroid lies inside
     * its footprint, otherwise the ground plane.
     */
    private fun contactSurfaceY(frame: SkeletonPose, point: SupportPoint): Float {
        val env = frame.environment
        val joints = SupportMath.jointsFor(point).map { frame.getJoint(it) }
        check(joints.isNotEmpty()) { "declared support point $point has no canonical joint family" }
        val cx = joints.sumOf { it.x.toDouble() }.toFloat() / joints.size
        val cz = joints.sumOf { it.z.toDouble() }.toFloat() / joints.size
        var surface = env.ground.level
        for (prop in env.props) {
            val cxProp = prop.centerX(); val czProp = prop.centerZ()
            val hw = prop.halfWidth(); val hd = prop.halfDepth()
            if (cx in (cxProp - hw)..(cxProp + hw) && cz in (czProp - hd)..(czProp + hd)) {
                when (prop) {
                    is BoxProp, is StepProp, is BenchProp -> surface = prop.topPlane()
                    is WallProp -> { /* a wall supports on its face; Y stays the ground reference */ }
                }
            }
        }
        return surface
    }

    private fun EnvironmentProp.centerX(): Float = when (this) {
        is BoxProp -> center.x; is StepProp -> center.x; is BenchProp -> center.x; is WallProp -> center.x
    }

    private fun EnvironmentProp.centerZ(): Float = when (this) {
        is BoxProp -> center.z; is StepProp -> center.z; is BenchProp -> center.z; is WallProp -> center.z
    }

    private fun EnvironmentProp.halfWidth(): Float = when (this) {
        is BoxProp -> width * 0.5f; is StepProp -> width * 0.5f; is BenchProp -> width * 0.5f; is WallProp -> width * 0.5f
    }

    private fun EnvironmentProp.halfDepth(): Float = when (this) {
        is BoxProp -> depth * 0.5f; is StepProp -> depth * 0.5f; is BenchProp -> depth * 0.5f; is WallProp -> depth * 0.5f
    }

    private fun EnvironmentProp.topPlane(): Float = when (this) {
        is BoxProp -> center.y + height * 0.5f
        is StepProp -> center.y + height * 0.5f
        is BenchProp -> center.y + height * 0.5f
        is WallProp -> center.y + height * 0.5f
    }

    private fun footJoints(point: SupportPoint) = SupportMath.jointsFor(point)

    private fun lowestContactY(frame: SkeletonPose, point: SupportPoint): Float =
        footJoints(point).minOf { frame.getJoint(it).y }

    private fun rows(label: String, frame: SkeletonPose): String =
        "  $label " + footJoints(SupportPoint.LEFT_FOOT).joinToString(" ") {
            "${it.name}=(${f(frame.getJoint(it).x)}, ${f(frame.getJoint(it).y)}, ${f(frame.getJoint(it).z)})"
        }

    // ---------------------------------------------------------------------------------------------
    // 1. The audit's symptom: the support foot's surface must be the declared step
    // ---------------------------------------------------------------------------------------------

    @Test
    fun thePlantedFootRestsOnTheDeclaredStepAndNotOnTheGround() {
        val step = declaredStep()
        val ground = declaredGround()
        val failures = mutableListOf<String>()
        val table = mutableListOf<String>()
        for ((condition, progress, frame) in frames()) {
            val surface = contactSurfaceY(frame, SupportPoint.LEFT_FOOT)
            val expected = if (progress in plantedSamples) step.top else ground
            table.add("$condition p=$progress: LEFT_FOOT resolved surface=${f(surface)} (expected ${f(expected)})")
            if (abs(surface - expected) > 0.01f) {
                failures.add(
                    "$condition p=$progress: the declared LEFT_FOOT support resolves to surface y=${f(surface)} " +
                        "instead of ${f(expected)} — the foot is not over the step the pose declares " +
                        "(step X[${f(step.x0)},${f(step.x1)}] Z[${f(step.z0)},${f(step.z1)}] top=${f(step.top)})"
                )
            }
        }
        assertTrue(
            "the Step-Up's support foot is not resolved to the step it declares (audit M1: the step never " +
                "supports anything):\n" + failures.joinToString("\n") + "\n" + table.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. No part of the planted foot overhangs the step
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theWholePlantedFootIsOnTheTread() {
        val step = declaredStep()
        val failures = mutableListOf<String>()
        val table = mutableListOf<String>()
        for ((condition, progress, frame) in frames()) {
            if (progress !in plantedSamples) continue
            table.add("$condition p=$progress: ${rows("LEFT_FOOT", frame)}")
            for (joint in footJoints(SupportPoint.LEFT_FOOT)) {
                val v = frame.getJoint(joint)
                val onTread = v.x in step.x0..step.x1 && v.z in step.z0..step.z1
                val aboveTop = v.y > step.top
                if (!onTread) {
                    failures.add(
                        "$condition p=$progress: $joint=(${f(v.x)}, ${f(v.y)}, ${f(v.z)}) is OUTSIDE the tread " +
                            "footprint X[${f(step.x0)},${f(step.x1)}] Z[${f(step.z0)},${f(step.z1)}] — the planted " +
                            "foot overhangs the step (audit M1)"
                    )
                }
                if (!aboveTop) {
                    failures.add(
                        "$condition p=$progress: $joint.y=${f(v.y)} is not above the step's top plane " +
                            "${f(step.top)} — the planted foot is inside the step"
                    )
                }
            }
        }
        assertTrue(
            "every joint of the declared planted-foot contact must lie on the tread:\n" +
                failures.joinToString("\n") + "\n" + table.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The foot's contact radius survives standing on the step
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theFootKeepsItsContactRadiusWhenItStandsOnTheStep() {
        val step = declaredStep()
        val ground = declaredGround()
        val failures = mutableListOf<String>()
        val table = mutableListOf<String>()
        for (condition in FrameCondition.entries) {
            val restOffset = lowestContactY(frameAt(condition, 0.0f), SupportPoint.LEFT_FOOT) - ground
            for (p in plantedSamples) {
                val frame = frameAt(condition, p)
                val plantedOffset = lowestContactY(frame, SupportPoint.LEFT_FOOT) - step.top
                table.add(
                    "$condition p=$p: floor-rest offset=${f(restOffset)}, on-step offset=${f(plantedOffset)}"
                )
                if (abs(plantedOffset - restOffset) > band) {
                    failures.add(
                        "$condition p=$p: the planted foot's contact joints ride ${f(plantedOffset)} above the " +
                            "step's top plane, but the same pose's floor-rest foot rides ${f(restOffset)} above the " +
                            "ground — the foot is not standing ON the step, it is ${f(plantedOffset - restOffset)} " +
                            "off the height a foot on that surface occupies"
                    )
                }
                if (plantedOffset <= 0f) {
                    failures.add("$condition p=$p: the planted foot is not above the step's top plane at all (${f(plantedOffset)})")
                }
            }
        }
        assertTrue(
            "a foot standing on a surface must ride its own contact radius above that surface " +
                "(the pose's floor rest, `PushUpPlank.BASE_ANKLE_HEIGHT`, and `DeclinePushUpPose`'s " +
                "foot-on-box relationship all say so):\n" + failures.joinToString("\n") + "\n" + table.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 4. The ascent buys the step's height
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theAscentBuysTheStepsHeight() {
        val step = declaredStep()
        val failures = mutableListOf<String>()
        val table = mutableListOf<String>()
        for (condition in FrameCondition.entries) {
            val heights = samples.map { frameAt(condition, it).getJoint(Joint.PELVIS).y }
            val rise = heights.max() - heights.min()
            table.add("$condition: pelvis $heights rise=${f(rise)} (step height ${f(step.top)})")
            if (rise < step.top) {
                failures.add(
                    "$condition: the pelvis rises ${f(rise)} while the declared step is ${f(step.top)} high — " +
                        "the athlete never gets onto the step"
                )
            }
            if (rise > step.top + breathBudget) {
                failures.add(
                    "$condition: the pelvis rises ${f(rise)}, more than the declared step ${f(step.top)} plus the " +
                        "breath budget ${f(breathBudget)} — the ascent no longer matches the step"
                )
            }
        }
        assertTrue(
            "the ascent must buy the step's height:\n" + failures.joinToString("\n") + "\n" + table.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 5. The trailing foot stays on the floor's side of the step
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theTrailingFootStaysOnTheFloorSideOfTheStep() {
        val step = declaredStep()
        val ground = declaredGround()
        val failures = mutableListOf<String>()
        val table = mutableListOf<String>()
        for ((condition, progress, frame) in frames()) {
            val surface = contactSurfaceY(frame, SupportPoint.RIGHT_FOOT)
            val lowest = lowestContactY(frame, SupportPoint.RIGHT_FOOT)
            table.add("$condition p=$progress: RIGHT_FOOT surface=${f(surface)} lowest=${f(lowest)}")
            if (abs(surface - ground) > 0.01f) {
                failures.add(
                    "$condition p=$progress: the trailing foot resolves to surface ${f(surface)} — it is not over " +
                        "the step (X[${f(step.x0)},${f(step.x1)}] Z[${f(step.z0)},${f(step.z1)}]) yet is declared as " +
                        "resting on it"
                )
            }
            if (lowest < ground - band) {
                failures.add("$condition p=$progress: the trailing foot is ${f(lowest)} — below its own floor")
            }
        }
        // BPS §7/§8: the trailing foot shares the floor with the lead foot at the seam and is lifted
        // CLEAR of it at the top. (The pose's floor-contact height is its own convention — the same
        // relationship [theFootKeepsItsContactRadiusWhenItStandsOnTheStep] asserts against the step —
        // so the seam is checked against the pose's other floor-resting foot, not against a literal 0.)
        for (condition in FrameCondition.entries) {
            val atTop = lowestContactY(frameAt(condition, 0.5f), SupportPoint.RIGHT_FOOT)
            val atSeam = lowestContactY(frameAt(condition, 0.0f), SupportPoint.RIGHT_FOOT)
            val leadAtSeam = lowestContactY(frameAt(condition, 0.0f), SupportPoint.LEFT_FOOT)
            if (abs(atSeam - leadAtSeam) > 0.01f) {
                failures.add(
                    "$condition: at the seam the trailing foot must be on the floor with the lead foot " +
                        "(${f(atSeam)} vs ${f(leadAtSeam)})"
                )
            }
            if (atTop - atSeam <= band) {
                failures.add(
                    "$condition: the trailing foot is not lifted clear of the floor at the top " +
                        "(seam ${f(atSeam)} -> top ${f(atTop)})"
                )
            }
        }
        assertTrue(
            "the trailing foot must stay on the floor's side of the step and lift clear of the floor at the top:\n" +
                failures.joinToString("\n") + "\n" + table.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 6. The invariant for the whole rep, plus the production carriers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun noSampledFramePenetratesItsResolvedSurfaceAndTheCarriersStayHonest() {
        val failures = mutableListOf<String>()
        val table = mutableListOf<String>()
        for ((condition, progress, frame) in frames()) {
            for (point in listOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT)) {
                val surface = contactSurfaceY(frame, point)
                for (joint in footJoints(point)) {
                    val v = frame.getJoint(joint)
                    if (v.y < surface - band) {
                        failures.add(
                            "$condition p=$progress: $joint.y=${f(v.y)} is ${f(v.y - surface)} below the surface " +
                                "${f(surface)} its own declaration rests on"
                        )
                    }
                }
            }
            table.add(
                "$condition p=$progress: maxIkClamp=${f(frame.maxIkClampAmount)} boneLen=${frame.boneLengthsVerified}"
            )
            if (frame.maxIkClampAmount != 0f) {
                failures.add("$condition p=$progress: maxIkClampAmount=${f(frame.maxIkClampAmount)} — a solve was clamped")
            }
            if (!frame.boneLengthsVerified) {
                failures.add("$condition p=$progress: boneLengthsVerified=false — a solved chain lost a segment length")
            }
        }
        assertTrue(
            "no declared contact may sit below its own surface, and the carriers must stay honest:\n" +
                failures.joinToString("\n") + "\n" + table.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Anti-vacuity: published, distinct, moving frames carrying the declaration
    // ---------------------------------------------------------------------------------------------

    @Test
    fun sampledFramesArePublishedDistinctSnapshots() {
        val captured = frames()
        assertEquals(samples.size * FrameCondition.entries.size, captured.size)
        assertEquals(
            "every sampled frame must be a distinct object (the pipeline publishes a reused buffer)",
            captured.size, captured.map { System.identityHashCode(it.third) }.distinct().size
        )
        val declared = builder().metadata.support.contacts.map { it.point }.toSet()
        assertTrue("the pose must declare its feet as support contacts", declared.isNotEmpty())
        for ((condition, progress, frame) in captured) {
            assertEquals(
                "$condition p=$progress: the published frame must carry the declared support model",
                declared, frame.supportedPoints.toSet()
            )
        }
        // The frames really are different observations of a moving body.
        val playing = captured.filter { it.first == FrameCondition.PLAYING }
        val hipHeights = playing.map { it.third.getJoint(Joint.PELVIS).y }
        assertTrue(
            "the sampled frames must be genuinely different geometry, not one frame measured five times " +
                "(PELVIS heights: $hipHeights)",
            hipHeights.distinct().size >= 2 && hipHeights.max() - hipHeights.min() > 1f
        )
        val leadTravel = playing.map { it.third.getJoint(Joint.ANKLE_F).z }
        assertTrue(
            "the planted foot's placement must actually be exercised by these samples (ANKLE_F.z: $leadTravel)",
            leadTravel.max() - leadTravel.min() > 1f
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Placed = fixed, and the loop seam is closed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theSupportFootIsFixedOnceItIsPlantedAndTheLoopSeamIsClosed() {
        for (condition in FrameCondition.entries) {
            val planted = plantedSamples.map { frameAt(condition, it).getJoint(Joint.ANKLE_F) }
            val xz = planted.map { it.x to it.z }
            val spread = xz.maxOf { (x, z) -> planted.maxOf { sqrt((x - it.x) * (x - it.x) + (z - it.z) * (z - it.z)) } }
            assertTrue(
                "$condition: the support foot must not slide once it is planted on the step (planted XZ=$xz, " +
                    "spread=${f(spread)})",
                spread <= 0.01f
            )
        }
        val seam = frameAt(FrameCondition.PLAYING, 0.0f)
        val end = frameAt(FrameCondition.PLAYING, 1.0f)
        var worst = 0f
        var worstJoint = ""
        for (joint in Joint.entries) {
            val a = seam.getJoint(joint); val b = end.getJoint(joint)
            val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
            if (d > worst) { worst = d; worstJoint = joint.name }
        }
        assertEquals(
            "this pose is PING_PONG: the rep's endpoints must coincide (worst delta ${f(worst)} at $worstJoint)",
            0f, worst, 1e-3f
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 9. Blast radius: every OTHER production pose class is untouched
    // ---------------------------------------------------------------------------------------------

    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside the corrected step-up must be byte-identical to the " +
                "pre-M1 tree (the digest covers every joint of every sampled frame of every other production " +
                "pose class); a change here means the correction leaked outside its scope. " +
                "measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }
    /** Every concrete production pose class in `poses/` except the corrected step-up. */
    private fun corpusDigest(): Long {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) { moduleRoot = dir; break }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate the app module root")
        val names = File(root, "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it == poseName }
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
         * Digest of the 50 unaffected production pose classes (every joint, every sampled frame,
         * `StepUpPose` excluded because it is the correction). Measured **equal on the pre-fix tree and
         * on the corrected tree** — i.e. the M1 change is confined to `StepUpPose`. Captured on
         * `origin/main` @ `1da6458` (pre-fix) and re-verified after the fix; its own mutation check is
         * the observed RED when the step-up is included in this digest.
         *
         * **Re-baselined by the M3/M5 prone-trunk correction** (`fix/m3-m5-prone-trunk-geometry`, off
         * `2bb4525`): this corpus is "every pose except the step-up", so it includes the three poses
         * that correction owns (`ProneCobraStretchPose`, `SupermanPose`, `ReverseSnowAngelPose`).
         * Observed RED on the pre-fix value `2746720065314572970` before the re-baseline; the same
         * recipe with those three classes ALSO excluded is byte-identical across the two trees
         * (`M3M5ProneTrunkGeometryTest.UNAFFECTED_CORPUS_DIGEST = -517042293001259057`), which is
         * where "the other 48 classes are untouched" is actually gated.
         *
         * **Re-baselined again by the M6/M7 swing/burpee correction** (`fix/m6-m7-swing-burpee-geometry`,
         * off `fc65695`): this corpus is "every pose except the step-up", so it includes the two poses
         * that correction owns (`KettlebellSwingPose`, `BurpeePose`). Observed RED on the previous value
         * `-8991724156081959456` before the re-baseline (the run measured the new value below). Attribution
         * is direct, not inferred: the whole-corpus dump (51 classes × 5 progress × every joint XYZ, `8415`
         * rows, full float bits) differs in exactly `268` rows, all of them those two poses; the other 49
         * classes are byte-identical, which `M6M7SwingBurpeeGeometryTest.UNAFFECTED_CORPUS_DIGEST`
         * (`-2275091341366878044`) gates directly.
         *
         * **Re-baselined again by the M8/M9/M10 support-declaration pass**
         * (`fix/m8-m9-m10-support-declaration`): that pass declares support for the 7 upper/dynamic
         * poses, the stretch family and the core/hip poses, and re-authors the 5 standing
         * upper/dynamic poses' limb targets into the chain-root frame — all of which live inside this
         * "every pose except the step-up" corpus. Observed RED on the pre-fix value
         * `-8991724156081959456`, and again on the M6/M7 value `6801737802461053843` after the pass was
         * rebased onto the M6/M7 merge (this pass originally branched off `fc65695`), before the
         * re-baseline below. Attribution is direct, not inferred from this digest: the whole-corpus dump
         * (51 classes × 5 progress × every joint XYZ, full float bits) measured on the rebased base and
         * on this tree differs only in rows belonging to the pass's own classes — gated by
         * `M8M9M10SupportDeclarationTest.UNAFFECTED_CORPUS_DIGEST`, which excludes exactly those classes
         * and is equal on both trees.
         *
         * **Re-baselined by the M13 hamstring forward-reach correction** (`fix/m13-hamstring-reach`,
         * off `0301563`): this corpus is "every production pose except the step-up", so it includes `HamstringStretchPose`, the one class that
         * correction owns. Observed RED on the pre-fix value `2391109884830495565` before the re-baseline (this
         * live run measured `6236906328909027759` below); the five scope digests were re-run with the pose file
         * stashed and all 50 of their tests were GREEN, so the delta is attributable to M13 and not
         * to a drifted base. Attribution is direct, not inferred from this digest: the whole-corpus
         * dump (49 registry poses × 5 progress × every joint XYZ, `245` pose-frames) differs in
         * exactly `1` frame — `hamstring_stretch_hold` at `p=0.0`, 12 arm-chain joints, max `0.8930`
         * u at `FINGERTIPS_A` — with the other `244` frames (including the subject's `p ≥ 0.05`)
         * byte-identical and `supportedPoints`/`maxIkClampAmount` unchanged everywhere. M13's own
         * blast-radius guard is `HamstringForwardReachTest.UNAFFECTED_CORPUS_DIGEST`.
         *
         * **Re-baselined by the M11/M12 limb-realization migration**
         * (`fix/m11-m12-limb-realization-migration`, off `a8d07cf`): this corpus means "every production
         * pose class except the one this pass corrects", so it contains `LatStretchPose` (M11 — the
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
         * below the pelvis. Observed RED on the previous value `-4852997236878182403` before the re-baseline (this live
         * run measured `-6608239793215088689`). Attribution is direct, not inferred: the whole-corpus dump (`51`
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
         * Observed RED on the previous value `-6608239793215088689` before the re-baseline (this live run measured
         * `-4254161156074832348`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
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
        * this correction removes in the same change). Observed RED on the previous value `-6608239793215088689` before
        * the re-baseline (this live run measured `3907844211770727281`). Attribution is direct, not inferred: the
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
         * B2-merged value `-4254161156074832348` before this re-baseline (this live run, with B1 integrated,
         * measured `6261922848910983622`). The digest's move from the B2-merged value is B1's own delta
         * (`DiamondPushUpPose`'s `ELBOW_A`/`ELBOW_P` plus the derived hand chain — `60` rows of the
         * whole-corpus dump, per B1's own record); from the pre-B2 value it is the union of B2's `20`
         * rows and B1's `60`.
         *
         * **Re-baselined by the B3 side-plank support-leg correction** (`fix/b3-sideplank-knee-plane`,
         * rebased onto the B2 merge `f8f8b24`, with B1 integrated): this corpus contains
         * `IsometricSidePlankPose`, the pose B3 corrects — the support leg's residual knee bend is
         * authored out of the mat now (the bend plane's pole `(0, -1, 0)` → `(0, 1, 0)`), so the pose
         * publishes `KNEE_B` above its own declared plane instead of `25.8897` below it. Observed RED on
         * the B1-integrated value `6261922848910983622` before this re-baseline (this live run measured `-2764093049021801384`).
         * Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5` samples × every
         * joint XYZ, `8415` rows, over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs from the pre-B3 tree in exactly `5` xyz rows — all `5` samples
         * of `IsometricSidePlankPose`'s `KNEE_B` — while B1's `60` `DiamondPushUpPose` rows and B2's `20`
         * `DynamicWorldsGreatestStretchPose` rows are untouched by this pass. B3's own gate is
         * `IsometricSidePlankKneePlaneTest`.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -2764093049021801384L
    }
}
