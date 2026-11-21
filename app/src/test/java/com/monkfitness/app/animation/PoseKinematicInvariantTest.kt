package com.monkfitness.app.animation

import com.monkfitness.app.anatomy.Segment
import com.monkfitness.app.anatomy.SegmentRest
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Four kinematic invariants every published frame must satisfy, swept over the WHOLE registered
 * corpus (`PoseRegistry`, both sides of an alternating exercise) at 64 steps of the rep:
 *
 *  1. **Bone rigidity** — a bone's two ends stay its rest length apart. A pose that "animates" by
 *     stretching a bone is drawing a body that cannot exist, and IK that is fed an unreachable
 *     target silently does exactly that.
 *  2. **Hinge range and bend plane** — the interior angle at every knee and elbow stays inside
 *     [0, STRAIGHT_MAX_DEG], and its bend plane never reverses between two consecutive frames while
 *     the joint is bent. An interior angle cannot express the *direction* a straight limb bends, so
 *     the plane is what catches a hyperextended knee: a knee that folds backwards has the same
 *     interior angle on both sides of the swap (measured: the burpee's reads 37.2 degrees either
 *     way), and only the plane tells the two apart.
 *  3. **Continuity** — no joint moves further than [STEP_JUMP_MAX_MM] in one 1/[STEPS] step, and a
 *     LOOP-mode rep comes back to its start. A teleport is the signature of a branch switch, a
 *     phase discontinuity or an uninitialised limb, and at 60 fps an 80 mm step is a visible snap.
 *  4. **Contact slip** — a contact a pose declares as resting on the environment does not travel
 *     horizontally while it is planted.
 *
 * Why a corpus sweep and not per-exercise assertions: the invariants are properties of the ENGINE,
 * and each of the four has already been violated by individual poses rather than by the solver. A
 * new exercise inherits the gate by being registered; nothing has to remember to add a test.
 *
 * Sampling and determinism, the two traps this file has to avoid:
 *  * The pipeline publishes the Finalizer's REUSED buffer, so every frame is copied BY VALUE.
 *  * A fresh pipeline's first frames are a settling transient (measured: the corpus's own framing
 *    work warms 11 frames), so each side is warmed at [WARM_UP_PROGRESS] before sampling, exactly as
 *    the renderer's own sweep does, and enters the pipeline through the SAME call the renderer makes
 *    (`produceFrame(builder, environment, supportedPoints)`) — the bare-builder overload carries no
 *    declaration, so the declaration-driven derivations would be inert and the test would measure
 *    geometry the athlete never sees.
 *
 * The pin tables are MEASUREMENTS, not tolerances. They state pre-existing outliers this file did
 * not fix, with the exact value measured on this tree. A new outlier fails; a pinned magnitude that
 * moves fails (the entry is a measurement and a pose change must re-measure it); a pinned entry that
 * stops being an outlier also fails, because that means it was fixed and the entry must go. An entry
 * leaves a table only by the defect being fixed.
 *
 * Two boundaries are deliberately not judged, and are stated here rather than silently skipped:
 *  * An `alternating` exercise plays each side as its OWN loop, so the step between the two sides is
 *    an intentional transition, not a teleport. Sides are swept as separate passes, and the
 *    loop-closure seam is not judged for an alternating exercise at all.
 *  * Only [LoopMode.LOOP] wraps its clock (`progress = 1 -> 0`); a PING_PONG / HOLD / ONCE rep
 *    reverses or stops at the end, so the seam is not a played frame pair there.
 */
class PoseKinematicInvariantTest {

    /** One published frame, copied out of the engine's reused buffer. */
    private class Frame(val progress: Float, val side: Side, val joint: Map<Joint, Vector3>) {
        operator fun get(j: Joint): Vector3 = joint.getValue(j)
    }

    /** A bone whose endpoints are both published joints: the pair a rigid segment must preserve. */
    private class Bone(val name: String, val proximal: Joint, val distal: Joint, val segment: Segment)

    /** A 2-bone hinge: the interior angle at [joint] between [proximal] and [distal]. */
    private class Hinge(val joint: Joint, val proximal: Joint, val distal: Joint)

    /** One measured bend-plane reversal: where, and the two angles either side of it. */
    private class Flip(val joint: Joint, val progress: Float, val from: Float, val to: Float) {
        val key: Pair<Joint, Float> get() = joint to progress
    }

    private fun context(progress: Float, side: Side) = PoseContext(
        progress = progress, side = side, definition = DEF, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Warms one side of one exercise, then captures its whole rep by value. */
    private fun sweep(id: String, side: Side): List<Frame> {
        val builder = PoseRegistry.getPoseConfig(id)!!.builder
        val pipeline = SkeletonPipeline(DEF)
        val environment = builder.metadata.environment
        val supported = builder.metadata.support.supportPoints
        for (k in 0 until WARM_UP_FRAMES) {
            pipeline.produceFrame(builder.build(context(WARM_UP_PROGRESS, side)), environment, supported)
        }
        val out = ArrayList<Frame>(STEPS + 1)
        for (i in 0..STEPS) {
            val progress = i.toFloat() / STEPS
            val published = pipeline.produceFrame(builder.build(context(progress, side)), environment, supported).pose
            val joint = HashMap<Joint, Vector3>(Joint.entries.size)
            for (j in Joint.entries) joint[j] = Vector3().set(published.getJoint(j))
            out.add(Frame(progress, side, joint))
        }
        return out
    }

    private fun distance(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The interior angle at [hinge], in degrees; NaN when the chain is degenerate. */
    private fun interiorAngle(frame: Frame, hinge: Hinge): Float {
        val u = Vector3().set(frame[hinge.proximal]).subtract(frame[hinge.joint])
        val w = Vector3().set(frame[hinge.distal]).subtract(frame[hinge.joint])
        val lengths = u.mag() * w.mag()
        if (lengths < 1e-5f) return Float.NaN
        val cos = ((u.x * w.x + u.y * w.y + u.z * w.z) / lengths).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    /** The unit normal of the hinge's bend plane, or null when the chain is straight. */
    private fun bendNormal(frame: Frame, hinge: Hinge): Vector3? {
        val u = Vector3().set(frame[hinge.proximal]).subtract(frame[hinge.joint])
        val w = Vector3().set(frame[hinge.distal]).subtract(frame[hinge.joint])
        val normal = Vector3()
        u.cross(w, normal)
        return if (normal.mag() < 1e-4f) null else normal.normalize()
    }

    /** The worst bone-length deviation of one exercise/side, per bone name, in millimetres. */
    private fun boneDeviations(frames: List<Frame>): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        for (bone in BONES) {
            val rest = SegmentRest.restLength(bone.segment, DEF)
            var worst = 0f
            for (frame in frames) {
                worst = max(worst, abs(distance(frame[bone.proximal], frame[bone.distal]) - rest) * MM_PER_UNIT)
            }
            out[bone.name] = worst
        }
        return out
    }

    /** Every bend-plane reversal of one exercise/side, in frame order. */
    private fun flips(frames: List<Frame>): List<Flip> {
        val out = mutableListOf<Flip>()
        for (hinge in HINGES) {
            var previous: Vector3? = null
            var previousAngle = 0f
            for (frame in frames) {
                val angle = interiorAngle(frame, hinge)
                // A nearly straight hinge states no plane: at 150 degrees the normal is already
                // conditioning noise, and a "flip" there would be a reading, not a bend swap.
                val normal = if (angle.isNaN() || angle > BEND_PLANE_CONDITION_MAX_DEG) null else bendNormal(frame, hinge)
                if (normal != null && previous != null) {
                    val dot = previous.x * normal.x + previous.y * normal.y + previous.z * normal.z
                    if (dot < 0f) out.add(Flip(hinge.joint, frame.progress, previousAngle, angle))
                }
                previous = normal
                previousAngle = angle
            }
        }
        return out
    }

    /** The largest displacement of any single joint between two consecutive sampled frames, in mm. */
    private fun worstStepMm(frames: List<Frame>): Float {
        var worst = 0f
        for (i in 0 until frames.size - 1) {
            for (j in Joint.entries) worst = max(worst, distance(frames[i][j], frames[i + 1][j]) * MM_PER_UNIT)
        }
        return worst
    }

    /** The largest displacement of any joint across the rep's seam (`progress = 1 -> 0`), in mm. */
    private fun seamMm(frames: List<Frame>): Float {
        var worst = 0f
        for (j in Joint.entries) worst = max(worst, distance(frames.last()[j], frames.first()[j]) * MM_PER_UNIT)
        return worst
    }

    /**
     * How far one declared contact travels horizontally, in millimetres, while it is PLANTED.
     *
     * Planted means "within [PLANTED_BAND_UNITS] of the lowest height this contact reaches in the rep"
     * — the stance window, stated on the contact's own geometry rather than on the ground level,
     * because a pose may declare its support on a box, a step or a bar. Contiguous planted frames
     * form one phase; the peak-to-peak horizontal travel inside the worst phase is the slip.
     */
    private fun slipMm(frames: List<Frame>, point: SupportPoint): Float {
        val anchor = SupportMath.anchorJointFor(point) ?: return 0f
        val heights = frames.map { it[anchor].y }
        val planted = heights.minOrNull()!! + PLANTED_BAND_UNITS
        var worst = 0f
        var i = 0
        while (i < heights.size) {
            if (heights[i] > planted) { i++; continue }
            var end = i
            while (end + 1 < heights.size && heights[end + 1] <= planted) end++
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (k in i..end) {
                val v = frames[k][anchor]
                minX = min(minX, v.x); maxX = max(maxX, v.x)
                minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
            }
            val dx = maxX - minX; val dz = maxZ - minZ
            worst = max(worst, sqrt(dx * dx + dz * dz) * MM_PER_UNIT)
            i = end + 1
        }
        return worst
    }

    /** Every declared contact of every side, measured: `exercise/side` -> point -> slip, in mm. */
    private fun contactSlipByPoint(): Map<String, Map<SupportPoint, Float>> {
        val out = LinkedHashMap<String, Map<SupportPoint, Float>>()
        for ((id, sides) in SWEEPS) {
            val builder = PoseRegistry.getPoseConfig(id)!!.builder
            val contacts = builder.metadata.support.contacts.map { it.point }.sortedBy { it.ordinal }
            if (contacts.isEmpty()) continue
            for ((side, frames) in sides) {
                val measured = LinkedHashMap<SupportPoint, Float>()
                for (point in contacts) measured[point] = slipMm(frames, point)
                out["$id/$side"] = measured
            }
        }
        return out
    }

    /**
     * Non-vacuity: the sweep is every registered exercise, at every step, on every side it plays.
     *
     * The corpus is the registry, not a hand-written list, so an exercise that is registered but
     * never played (or a sweep that silently drops a side) is a failure here rather than a set of
     * invariants quietly measuring less than they claim.
     */
    @Test
    fun everyRegisteredExerciseIsSweptAtEveryStepOnEverySideItPlays() {
        val ids = PoseRegistry.animationIds.sorted()
        assertTrue("the registry must contain the exercise corpus (found ${ids.size})", ids.size >= 60)
        assertEquals("every registered exercise must be swept", ids, SWEEPS.keys.sorted())

        var frames = 0
        for (id in ids) {
            val config = PoseRegistry.getPoseConfig(id)!!
            val sides = SWEEPS.getValue(id)
            assertEquals(
                "$id: an alternating exercise plays both sides, every other exercise one",
                if (config.alternating) setOf(Side.RIGHT, Side.LEFT) else setOf(Side.RIGHT),
                sides.keys
            )
            for ((side, samples) in sides) {
                assertEquals("$id/$side: every step of the rep must be sampled", STEPS + 1, samples.size)
                assertEquals("$id/$side: the first sample is progress 0", 0f, samples.first().progress, 0f)
                assertEquals("$id/$side: the last sample is progress 1", 1f, samples.last().progress, 0f)
                for (i in samples.indices) {
                    assertEquals(
                        "$id/$side sample $i: the progress step must be exactly 1/$STEPS",
                        i.toFloat() / STEPS, samples[i].progress, 1e-7f
                    )
                    assertEquals("$id/$side sample $i: all 33 joints must be captured", Joint.entries.size, samples[i].joint.size)
                }
                frames += samples.size
            }
        }
        assertTrue("anti-vacuity: the sweep must cover the whole corpus (sampled $frames frames)", frames >= 4000)
    }

    /** Invariant 1: no bone telescopes — its two ends stay its rest length apart. */
    @Test
    fun everyBoneKeepsItsRestLengthOnEveryFrameOfEveryExercise() {
        val measured = LinkedHashMap<String, Map<String, Float>>()
        for ((id, sides) in SWEEPS) {
            val perBone = LinkedHashMap<String, Float>()
            for ((_, frames) in sides) {
                for ((bone, deviation) in boneDeviations(frames)) {
                    perBone[bone] = max(perBone[bone] ?: 0f, deviation)
                }
            }
            val outliers = perBone.filterValues { it >= BONE_DEVIATION_MAX_MM }
            if (outliers.isNotEmpty()) measured[id] = outliers
        }

        val unattributed = measured.filterKeys { it !in knownSegmentStretch }
        assertTrue(
            "bones whose published length leaves the rest length by more than $BONE_DEVIATION_MAX_MM mm " +
                "(IK fed a target outside the limb's reach realizes a stretched bone):\n" +
                unattributed.entries.joinToString("\n") { (id, bones) ->
                    "  $id " + bones.entries.joinToString { "${it.key}=${it.value} mm" }
                },
            unattributed.isEmpty()
        )

        val stale = knownSegmentStretch.keys - measured.keys
        assertTrue(
            "these pinned stretch entries no longer deviate — the pose was fixed, so the entry must go:\n" +
                stale.joinToString("\n") { "  $it" },
            stale.isEmpty()
        )

        for ((id, bones) in knownSegmentStretch) {
            for ((bone, pinned) in bones) {
                val actual = measured[id]?.get(bone)
                assertTrue(
                    "$id $bone: a pinned entry may only name a bone that STILL deviates (measured $actual, pinned $pinned)",
                    actual != null && actual >= BONE_DEVIATION_MAX_MM
                )
                assertEquals(
                    "$id $bone: the pinned deviation is a measurement, not a tolerance — a pose change that " +
                        "moves it must update the entry",
                    pinned, actual!!, PIN_TOLERANCE_MM
                )
            }
        }

    }

    /** Invariant 2a: a knee or elbow stays inside its angular range and never degenerates. */
    @Test
    fun kneesAndElbowsStayInsideTheirAngularRangeOnEveryFrame() {
        var deepestFlexion = 180f
        var straightest = 0f
        val outside = mutableListOf<String>()
        for ((id, sides) in SWEEPS) {
            for ((side, frames) in sides) {
                for (hinge in HINGES) {
                    for (frame in frames) {
                        val angle = interiorAngle(frame, hinge)
                        if (angle.isNaN()) {
                            // A coincident proximal/distal joint states no angle at all: the limb has
                            // collapsed, which is a worse defect than being out of range.
                            outside.add("$id/$side ${hinge.joint} at progress ${frame.progress}: degenerate chain")
                            continue
                        }
                        deepestFlexion = min(deepestFlexion, angle)
                        straightest = max(straightest, angle)
                        if (angle < 0f || angle > STRAIGHT_MAX_DEG) {
                            outside.add("$id/$side ${hinge.joint} at progress ${frame.progress}: $angle deg")
                        }
                    }
                }
            }
        }
        assertTrue(
            "hinges outside 0..$STRAIGHT_MAX_DEG deg:\n" + outside.take(20).joinToString("\n"),
            outside.isEmpty()
        )
        // Non-vacuity: the corpus must actually reach both a straight limb and a deep hinge, or the
        // band is satisfied by geometry that never approaches it.
        assertTrue("the corpus must reach a straight knee/elbow (measured max $straightest)", straightest >= 170f)
        assertTrue("the corpus must reach a deep hinge (measured min $deepestFlexion)", deepestFlexion <= 40f)
    }

    /**
     * Invariant 2b: a bent hinge's bend plane never reverses between consecutive frames.
     *
     * This is the reading an angle-only check cannot make: the burpee's knee sits at 37 degrees on
     * both sides of its reversal, so it is in range throughout while the leg visibly swaps the way it
     * folds.
     */
    @Test
    fun noHingeInvertsItsBendPlaneMidRep() {
        val measured = LinkedHashMap<String, List<Flip>>()
        for ((id, sides) in SWEEPS) {
            val all = mutableListOf<Flip>()
            for ((_, frames) in sides) all.addAll(flips(frames))
            if (all.isNotEmpty()) measured[id] = all.sortedWith(compareBy({ it.joint.ordinal }, { it.progress }))
        }

        val unattributed = measured.filterKeys { it !in knownBendPlaneFlips }
        assertTrue(
            "hinges that reverse their bend plane between two consecutive frames:\n" +
                unattributed.entries.joinToString("\n") { (id, list) ->
                    "  $id " + list.joinToString { "${it.joint}@${it.progress} (${it.from}->${it.to} deg)" }
                },
            unattributed.isEmpty()
        )

        val stale = knownBendPlaneFlips.keys - measured.keys
        assertTrue(
            "these pinned bend-plane reversals no longer occur — the pose was fixed, so the entry must go:\n" +
                stale.joinToString("\n") { "  $it" },
            stale.isEmpty()
        )

        for ((id, pinned) in knownBendPlaneFlips) {
            val actual = measured[id].orEmpty()
            assertEquals(
                "$id: the pinned reversals must be exactly the measured ones (measured " +
                    actual.joinToString { "${it.joint}@${it.progress}" } + ")",
                pinned.map { it.key }.sortedBy { it.second }, actual.map { it.key }.sortedBy { it.second }
            )
            for (expected in pinned) {
                val match = actual.first { it.key == expected.key }
                assertEquals(
                    "$id ${expected.joint}@${expected.progress}: the pinned angle is a measurement",
                    expected.to, match.to, PIN_TOLERANCE_DEG
                )
                assertEquals(
                    "$id ${expected.joint}@${expected.progress}: the pinned angle is a measurement",
                    expected.from, match.from, PIN_TOLERANCE_DEG
                )
            }
        }
    }

    /**
     * Invariant 3a: no joint teleports between two consecutive sampled frames.
     *
     * The threshold is the same 80 mm per 1/64 of a rep that [knownStepJumps] records; a step that
     * fast is a branch switch or a phase discontinuity, not motion.
     */
    @Test
    fun noJointTeleportsBetweenConsecutiveSteps() {
        val measured = LinkedHashMap<String, Float>()
        for ((id, sides) in SWEEPS) {
            var worst = 0f
            for ((_, frames) in sides) worst = max(worst, worstStepMm(frames))
            measured[id] = worst
        }

        val over = measured.filterValues { it > STEP_JUMP_MAX_MM }
        val unattributed = over.filterKeys { it !in knownStepJumps }
        assertTrue(
            "joints that move more than $STEP_JUMP_MAX_MM mm in one 1/$STEPS step:\n" +
                unattributed.entries.joinToString("\n") { (id, mm) -> "  $id: $mm mm" },
            unattributed.isEmpty()
        )

        val stale = knownStepJumps.keys - over.keys
        assertTrue(
            "these pinned step jumps are gone — the pose was fixed, so the entry must go:\n" +
                stale.joinToString("\n") { "  $it" },
            stale.isEmpty()
        )

        for ((id, pinned) in knownStepJumps) {
            val actual = over[id]
            assertTrue(
                "$id: a pinned entry may only name an exercise that STILL jumps (measured $actual, pinned $pinned)",
                actual != null && actual > STEP_JUMP_MAX_MM
            )
            assertEquals(
                "$id: the pinned step jump is a measurement, not a tolerance",
                pinned, actual!!, PIN_TOLERANCE_MM
            )
        }
    }

    /**
     * Invariant 3b: a LOOP-mode rep returns to its start.
     *
     * Only LOOP wraps its clock (`progress = 1 -> 0`); the other modes reverse or stop at the end and
     * an alternating exercise plays its sides as separate loops, so their seams are not played frame
     * pairs and are excluded rather than pinned.
     */
    @Test
    fun aLoopModeRepReturnsToItsStart() {
        val measured = LinkedHashMap<String, Float>()
        val excluded = mutableListOf<String>()
        for ((id, sides) in SWEEPS) {
            val config = PoseRegistry.getPoseConfig(id)!!
            if (config.alternating || config.builder.metadata.loopMode != LoopMode.LOOP) {
                excluded.add(id)
                continue
            }
            measured[id] = seamMm(sides.getValue(Side.RIGHT))
        }

        assertTrue("anti-vacuity: some exercises must be LOOP-mode (excluded ${excluded.size})", measured.size >= 20)

        val over = measured.filterValues { it > STEP_JUMP_MAX_MM }
        val unattributed = over.filterKeys { it !in knownLoopSeamJumps }
        assertTrue(
            "LOOP-mode reps whose seam moves a joint more than $STEP_JUMP_MAX_MM mm (progress 1 -> 0):\n" +
                unattributed.entries.joinToString("\n") { (id, mm) -> "  $id: $mm mm" },
            unattributed.isEmpty()
        )

        val stale = knownLoopSeamJumps.keys - over.keys
        assertTrue(
            "these pinned seams are gone — the pose was fixed, so the entry must go:\n" +
                stale.joinToString("\n") { "  $it" },
            stale.isEmpty()
        )

        for ((id, pinned) in knownLoopSeamJumps) {
            val actual = over[id]
            assertTrue(
                "$id: a pinned entry may only name a rep that STILL does not close (measured $actual, pinned $pinned)",
                actual != null && actual > STEP_JUMP_MAX_MM
            )
            assertEquals(
                "$id: the pinned seam is a measurement, not a tolerance",
                pinned, actual!!, PIN_TOLERANCE_MM
            )
        }
    }

    /**
     * Invariant 4: a declared contact does not slide horizontally while it is planted.
     *
     * The two authored cases the corpus is built around are exact: `pushup_standard`'s and
     * `row_standard`'s hands are placed at a fixed world point every frame, so their slip is zero to
     * float precision. Every other declared contact that drifts is recorded in [knownContactSlip] with
     * the depth of its drift — a floor, not a licence: the pinned value is re-measured on every run.
     */
    @Test
    fun declaredContactsDoNotSlideWhileTheyArePlanted() {
        val measured = contactSlipByPoint()

        val drifting = LinkedHashMap<String, Map<String, Float>>()
        for ((key, byPoint) in measured) {
            val over = byPoint.filterValues { it > CONTACT_SLIP_MAX_MM }
            if (over.isEmpty()) continue
            drifting[key] = over.entries.associate { it.key.name to it.value }
        }

        val unattributed = drifting.filterKeys { it !in knownContactSlip }
        assertTrue(
            "declared contacts that travel more than $CONTACT_SLIP_MAX_MM mm horizontally while planted:\n" +
                unattributed.entries.joinToString("\n") { (key, points) ->
                    "  $key " + points.entries.joinToString { "${it.key}=${it.value} mm" }
                },
            unattributed.isEmpty()
        )

        val stale = knownContactSlip.keys - drifting.keys
        assertTrue(
            "these pinned contacts no longer slide — the pose was fixed, so the entry must go:\n" +
                stale.joinToString("\n") { "  $it" },
            stale.isEmpty()
        )

        for ((key, pinned) in knownContactSlip) {
            for ((point, mm) in pinned) {
                val actual = drifting[key]?.get(point)
                assertTrue(
                    "$key $point: a pinned entry may only name a contact that STILL drifts (measured $actual, pinned $mm)",
                    actual != null && actual > CONTACT_SLIP_MAX_MM
                )
                assertEquals(
                    "$key $point: the pinned slip is a measurement, not a tolerance",
                    mm, actual!!, PIN_TOLERANCE_MM
                )
            }
        }

        // The two authored statements the corpus makes about static support: an authored hand is at a
        // fixed world point every frame, so its travel is zero, not "small".
        for (id in listOf("pushup_standard", "row_standard")) {
            for (point in listOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND)) {
                assertEquals(
                    "$id $point: an authored hand must be planted at exactly one point for the whole rep",
                    0f, measured.getValue("$id/${Side.RIGHT}").getValue(point), EXACT_TOLERANCE_MM
                )
            }
        }

        // Non-vacuity: the measurement must cover every declaring exercise on every side it plays —
        // the count is implied by the declarations, so a skipped pose cannot hide in it.
        val declaringSides = SWEEPS.entries.sumOf { (id, sides) ->
            if (PoseRegistry.getPoseConfig(id)!!.builder.metadata.support.contacts.isEmpty()) 0 else sides.size
        }
        assertEquals("every declaring exercise side must be measured", declaringSides, measured.size)
        val measuredContacts = measured.values.sumOf { it.size }
        assertTrue(
            "anti-vacuity: the corpus must declare the contacts it plants (measured $measuredContacts)",
            measuredContacts >= 150
        )
    }

    /** A copy of [frame] with some joints moved, so a measurement can be shown to report a defect. */
    private fun moved(frame: Frame, vararg changes: Pair<Joint, Vector3>): Frame {
        val joint = HashMap(frame.joint)
        for ((id, position) in changes) joint[id] = position
        return Frame(frame.progress, frame.side, joint)
    }

    private fun at(x: Float, y: Float, z: Float) = Vector3(x, y, z)

    /** A real published frame of one exercise, for the controls: they perturb measured geometry. */
    private fun aFrame(id: String = "pushup_standard"): Frame = SWEEPS.getValue(id).getValue(Side.RIGHT).first()

    /** Control: a bone whose ends were pulled apart is reported, and an untouched one is not. */
    @Test
    fun theBoneMeasureReportsATelescopedBone() {
        val frame = aFrame()
        val knee = frame[Joint.KNEE_F]
        // 10% of a femur along the lateral axis: a visible stretch, far inside any anatomical excuse.
        val stretched = moved(
            frame,
            Joint.KNEE_F to at(knee.x + SegmentRest.restLength(Segment.FEMUR, DEF) * 0.1f, knee.y, knee.z)
        )
        val untouched = boneDeviations(listOf(frame)).getValue("HIP-KNEE_F")
        val reported = boneDeviations(listOf(stretched)).getValue("HIP-KNEE_F")
        assertTrue("the unperturbed frame must be inside the band (measured $untouched mm)", untouched < BONE_DEVIATION_MAX_MM)
        assertTrue("a 10% stretch must be reported (measured $reported mm)", reported >= BONE_DEVIATION_MAX_MM)
    }

    /** Control: the hinge measures report a fold reversal, a collapsed chain and a straight chain. */
    @Test
    fun theHingeMeasuresReportAReversalACollapseAndAFullExtension() {
        val frame = aFrame()
        val bentOneWay = moved(frame, Joint.HIP_F to at(0f, 100f, 0f), Joint.KNEE_F to at(0f, 0f, 0f), Joint.ANKLE_F to at(100f, 0f, 0f))
        val bentTheOther = moved(frame, Joint.HIP_F to at(0f, 100f, 0f), Joint.KNEE_F to at(0f, 0f, 0f), Joint.ANKLE_F to at(-100f, 0f, 0f))

        // Same interior angle on both sides of the swap: an angle-only reading cannot see the reversal.
        assertEquals("the folded-frame angle", 90f, interiorAngle(bentOneWay, HINGES[0]), 0.1f)
        assertEquals("the reversed-fold angle", 90f, interiorAngle(bentTheOther, HINGES[0]), 0.1f)
        assertEquals(
            "the reversal must be reported even though the angle is identical",
            1, flips(listOf(bentOneWay, bentTheOther)).size
        )

        // A chain whose distal end sits on the joint has collapsed: no angle, reported as such.
        val collapsed = moved(frame, Joint.KNEE_F to at(0f, 0f, 0f), Joint.ANKLE_F to at(0f, 0f, 0f))
        assertTrue("a collapsed chain must read as no angle", interiorAngle(collapsed, HINGES[0]).isNaN())

        // A fully extended chain reads as straight, inside the range.
        val straight = moved(frame, Joint.HIP_F to at(0f, 100f, 0f), Joint.KNEE_F to at(0f, 0f, 0f), Joint.ANKLE_F to at(0f, -120f, 0f))
        assertEquals("a straight chain reads as fully extended", 180f, interiorAngle(straight, HINGES[0]), 0.01f)
        assertTrue("a straight chain is inside the range", interiorAngle(straight, HINGES[0]) <= STRAIGHT_MAX_DEG)
    }

    /** Control: a joint that moves further than the bound in one step is reported, a still one is not. */
    @Test
    fun theContinuityMeasureReportsATeleport() {
        val frames = SWEEPS.getValue("pushup_standard").getValue(Side.RIGHT).take(2)
        val still = worstStepMm(listOf(frames[0], frames[0]))
        val chest = frames[1][Joint.CHEST]
        val jumped = worstStepMm(listOf(frames[0], moved(frames[1], Joint.CHEST to at(chest.x + 30f, chest.y, chest.z))))

        assertEquals("an unchanged pair of frames must not read as motion", 0f, still, 1e-4f)
        assertTrue("a 30-unit step must be reported (measured $jumped mm)", jumped > STEP_JUMP_MAX_MM)
        assertTrue("the seam measure must see the same displacement", seamMm(listOf(frames[0], moved(frames[1], Joint.CHEST to at(chest.x + 30f, chest.y, chest.z)))) > STEP_JUMP_MAX_MM)
    }

    /** Control: a planted contact that travels horizontally is reported, an authored one is not. */
    @Test
    fun theSlipMeasureReportsASlidingContact() {
        val frames = SWEEPS.getValue("pushup_standard").getValue(Side.RIGHT)
        val authored = slipMm(frames, SupportPoint.LEFT_HAND)
        val slid = slipMm(
            frames.mapIndexed { i, frame ->
                val hand = frame[Joint.HAND_A]
                moved(frame, Joint.HAND_A to at(hand.x + i * 0.5f, hand.y, hand.z))
            },
            SupportPoint.LEFT_HAND
        )
        assertEquals("an authored hand must read as planted", 0f, authored, EXACT_TOLERANCE_MM)
        assertTrue("a hand drifting 0.5 units a step must be reported (measured $slid mm)", slid > CONTACT_SLIP_MAX_MM)
    }

    private companion object {
        val DEF = SkeletonDefinition.DEFAULT_ADULT

        /** Steps per rep. A step is exactly `1/STEPS` of progress, the interval the jump bound is per. */
        const val STEPS = 64

        /** The engine's cold-start fixed point, as the renderer's own sweep warms it. */
        const val WARM_UP_FRAMES = 11
        const val WARM_UP_PROGRESS = 0.3f

        /**
         * Millimetres per engine unit at the default definition's scale: the pose builders' rest
         * pelvis height (leg chain plus the `25`-unit floor clearance) over the `0.95 m` trochanter
         * height the rig scale registers against, inverted — the same ratio `X1Bind.unitsPerMetre`
         * applies, restated here because the motion core may not import the character side.
         */
        val MM_PER_UNIT: Float = 1000f / (
            (DEF.shinLength + DEF.thighLength + PELVIS_REST_LIFT) / PELVIS_HEIGHT_METRES
            )

        const val PELVIS_REST_LIFT = 25f
        const val PELVIS_HEIGHT_METRES = 0.95f

        /** Bone-length tolerance: 0.5 mm, the tolerance the rig bind is held to. */
        const val BONE_DEVIATION_MAX_MM = 0.5f

        /**
         * A hinge's interior angle cannot exceed 180 degrees: this is 180 plus the 2 degrees of slack a
         * deliberately straight limb is allowed. A reading above it is a degenerate chain.
         */
        const val STRAIGHT_MAX_DEG = 182f

        /** Above this angle the bend plane is conditioning noise, not a stated plane. */
        const val BEND_PLANE_CONDITION_MAX_DEG = 150f

        /** A step or seam displacement above this is a teleport, not motion: 80 mm per 1/64 rep. */
        const val STEP_JUMP_MAX_MM = 80f

        /** A planted contact travelling further than this is sliding, not planted. */
        const val CONTACT_SLIP_MAX_MM = 0.5f

        /** A contact's stance window: within this height band of the lowest it reaches in the rep. */
        const val PLANTED_BAND_UNITS = 1.0f

        /** How close a pinned measurement must reproduce. */
        const val PIN_TOLERANCE_MM = 0.05f
        const val PIN_TOLERANCE_DEG = 0.1f

        /** Float noise on an authored point: a planted hand's travel is zero, not approximately zero. */
        const val EXACT_TOLERANCE_MM = 0.001f

        /** Every published rep, by exercise and side, warmed exactly as the renderer warms it. */
        val SWEEPS: Map<String, Map<Side, List<Frame>>> by lazy {
            val out = LinkedHashMap<String, Map<Side, List<Frame>>>()
            val test = PoseKinematicInvariantTest()
            for (id in PoseRegistry.animationIds.sorted()) {
                val alternating = PoseRegistry.getPoseConfig(id)!!.alternating
                val sides = if (alternating) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
                out[id] = sides.associateWith { test.sweep(id, it) }
            }
            out
        }

        /** The nine bones whose rest length a frame must preserve: five segments, both sides. */
        val BONES = listOf(
            Bone("HIP-KNEE_F", Joint.HIP_F, Joint.KNEE_F, Segment.FEMUR),
            Bone("HIP-KNEE_B", Joint.HIP_B, Joint.KNEE_B, Segment.FEMUR),
            Bone("KNEE-ANKLE_F", Joint.KNEE_F, Joint.ANKLE_F, Segment.TIBIA),
            Bone("KNEE-ANKLE_B", Joint.KNEE_B, Joint.ANKLE_B, Segment.TIBIA),
            Bone("SHOULDER-ELBOW_A", Joint.SHOULDER_A, Joint.ELBOW_A, Segment.HUMERUS),
            Bone("SHOULDER-ELBOW_P", Joint.SHOULDER_P, Joint.ELBOW_P, Segment.HUMERUS),
            Bone("ELBOW-WRIST_A", Joint.ELBOW_A, Joint.WRIST_A, Segment.FOREARM),
            Bone("ELBOW-WRIST_P", Joint.ELBOW_P, Joint.WRIST_P, Segment.FOREARM),
            Bone("PELVIS-CHEST", Joint.PELVIS, Joint.CHEST, Segment.PELVIS)
        )

        /** The four hinges: both knees and both elbows, read as proximal -> joint -> distal. */
        val HINGES = listOf(
            Hinge(Joint.KNEE_F, Joint.HIP_F, Joint.ANKLE_F),
            Hinge(Joint.KNEE_B, Joint.HIP_B, Joint.ANKLE_B),
            Hinge(Joint.ELBOW_A, Joint.SHOULDER_A, Joint.WRIST_A),
            Hinge(Joint.ELBOW_P, Joint.SHOULDER_P, Joint.WRIST_P)
        )

        /**
         * Bones that do not hold their rest length, with the worst measured deviation in millimetres.
         *
         * Both are the same defect, on the same bone: an authored hand target that leaves the arm's
         * reachable annulus, so `clampTargetToReach` realizes the hand somewhere else and the forearm
         * publishes longer than its bone. `pike_pushup_standard` asks for a hand 54 units beyond the
         * reach at the top of its rep; `row_standard` leaves the band by 10, reduced to 9.7 by the
         * girdle drive carrying its glenoid back.
         */
        val knownSegmentStretch: Map<String, Map<String, Float>> = mapOf(
            "pike_pushup_standard" to mapOf("ELBOW-WRIST_A" to 219.631f),
            "row_standard" to mapOf("ELBOW-WRIST_A" to 38.379f)
        )

        /**
         * Exercises with a teleport at some step, with the worst measured step in millimetres.
         *
         * Every entry is a branch switch or a phase discontinuity inside the authored rep (the burpee
         * swings its knees round at the plant, the y-jack's arm flips at the top, the child's pose
         * hands snap as the hips fold), recorded so a NEW jump cannot be introduced silently. The row's
         * worst step is measured with its girdle drive authored.
         */
        val knownStepJumps: Map<String, Float> = mapOf(
            "burpee_standard" to 841.302f,
            "child_pose_hold" to 207.191f,
            "dead_bug_standard" to 158.059f,
            "face_pull_banded" to 153.630f,
            "jumping_jack_standard" to 210.214f,
            "ninety_ninety_hips" to 146.941f,
            "reverse_snow_angel_prone" to 98.633f,
            "row_standard" to 121.848f,
            "shoulder_cars_standard" to 84.090f,
            "squat_jump" to 96.600f,
            "superman_prone" to 80.123f,
            "yt_raises_standard" to 461.713f
        )

        /**
         * LOOP-mode exercises whose rep does not come back to its start, with the measured seam.
         *
         * These are authored as a one-way excursion and played by a mode that wraps the clock, so the
         * pose jumps at the loop boundary. Recorded, not fixed: which end of the rep a mode should
         * return from is a per-exercise authoring decision.
         */
        val knownLoopSeamJumps: Map<String, Float> = mapOf(
            "ankle_mobility_standard" to 132.412f,
            "band_pull_aparts_standard" to 724.951f,
            "calf_stretch_hold" to 138.698f,
            "cat_cow_reps" to 139.881f,
            "face_pull_banded" to 416.900f,
            "glute_bridge_standard" to 230.995f,
            "horse_stance_hold" to 239.873f,
            "ninety_ninety_hips" to 1283.678f,
            "pike_pushup_standard" to 434.276f,
            "scapular_retraction_hold" to 197.151f,
            "squat_standard" to 787.894f,
            "squat_sumo" to 796.250f,
            "superman_prone" to 316.989f,
            "wall_sit_hold" to 291.395f,
            "wall_slide_standard" to 507.002f
        )

        /**
         * Hinges that swap their bend plane mid-rep, with the measured angles either side.
         *
         * The burpee's knees fold round the other way at the plant and again on the way back up, and
         * the jacks' elbows swap the way they bend at the arms' top of swing. Both sit inside the
         * angular range on both sides of the reversal, so only the plane reveals them.
         */
        val knownBendPlaneFlips: Map<String, List<Flip>> = mapOf(
            "burpee_standard" to listOf(
                Flip(Joint.KNEE_F, 0.265625f, 37.226f, 38.845f),
                Flip(Joint.KNEE_F, 0.75f, 38.845f, 37.226f),
                Flip(Joint.KNEE_B, 0.265625f, 37.226f, 38.845f),
                Flip(Joint.KNEE_B, 0.75f, 38.845f, 37.226f)
            ),
            "jumping_jack_standard" to listOf(
                Flip(Joint.ELBOW_A, 0.203125f, 137.135f, 137.135f),
                Flip(Joint.ELBOW_A, 0.8125f, 137.135f, 137.135f),
                Flip(Joint.ELBOW_P, 0.203125f, 137.135f, 137.135f),
                Flip(Joint.ELBOW_P, 0.8125f, 137.135f, 137.135f)
            )
        )

        /**
         * Declared contacts that travel horizontally while planted, with the measured worst phase, in
         * millimetres. A large value is not always a defect — the reverse snow angel sweeps its hands
         * along the mat on purpose, and a burpee's plant moves — but every one of them is a measured
         * fact, so re-authoring a pose that changes one has to say so here.
         */
        val knownContactSlip: Map<String, Map<String, Float>> = mapOf(
            "birddog_hold/LEFT" to mapOf("LEFT_KNEE" to 57.784f),
            "birddog_hold/RIGHT" to mapOf("LEFT_KNEE" to 57.784f),
            "cobra_stretch_hold/RIGHT" to mapOf("LEFT_HAND" to 96.849f, "RIGHT_HAND" to 96.849f),
            "cossack_squat/RIGHT" to mapOf("LEFT_FOOT" to 719.067f, "RIGHT_FOOT" to 719.067f),
            "glute_bridge_standard/RIGHT" to mapOf("LEFT_FOOT" to 27.497f, "RIGHT_FOOT" to 27.497f),
            "hip_flexor_stretch_hold/RIGHT" to mapOf("RIGHT_FOOT" to 2.702f),
            "lunge_forward/RIGHT" to mapOf("LEFT_FOOT" to 3.418f, "RIGHT_FOOT" to 3.418f),
            "lunge_reverse/RIGHT" to mapOf("LEFT_FOOT" to 3.340f, "RIGHT_FOOT" to 3.340f),
            "lunge_side/RIGHT" to mapOf("LEFT_FOOT" to 2.796f, "RIGHT_FOOT" to 2.796f),
            "pike_pushup_standard/RIGHT" to mapOf("LEFT_TOES" to 3.436f),
            "plank_standard/RIGHT" to mapOf("LEFT_FOREARM" to 1.825f, "RIGHT_FOREARM" to 1.825f),
            "pushup_decline/RIGHT" to mapOf("LEFT_TOES" to 9.682f, "RIGHT_TOES" to 9.682f),
            "pushup_diamond/RIGHT" to mapOf("LEFT_TOES" to 9.682f, "RIGHT_TOES" to 9.682f),
            "pushup_knee/RIGHT" to mapOf("LEFT_KNEE" to 15.480f, "RIGHT_KNEE" to 15.480f),
            "pushup_military/RIGHT" to mapOf("LEFT_TOES" to 9.682f, "RIGHT_TOES" to 9.682f),
            "pushup_standard/RIGHT" to mapOf("LEFT_TOES" to 9.682f, "RIGHT_TOES" to 9.682f),
            "pushup_wide/RIGHT" to mapOf("LEFT_TOES" to 9.682f, "RIGHT_TOES" to 9.682f),
            "reverse_snow_angel_prone/RIGHT" to mapOf("LEFT_HAND" to 1232.615f, "RIGHT_HAND" to 1232.615f),
            "side_plank_standard/RIGHT" to mapOf("RIGHT_FOREARM" to 0.794f),
            "step_up_standard/RIGHT" to mapOf("LEFT_FOOT" to 0.516f),
            "superman_prone/RIGHT" to mapOf("LEFT_HAND" to 0.693f, "RIGHT_HAND" to 0.693f),
            "world_greatest_stretch/RIGHT" to mapOf("RIGHT_HAND" to 4.518f)
        )
    }
}
