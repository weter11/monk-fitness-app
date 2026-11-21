package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Vector3

/** The no-value convention for a segment and an obstacle index; never read as `0`. */
const val NO_INDEX = -1

/**
 * Whether the solver reached a head, and what it said. [NOT_PROCESSED] is the declared zero value,
 * so a fresh `IntArray` is already correct and "never reached" cannot read as "rejected".
 */
enum class PathStatus { NOT_PROCESSED, VALID, INVALID }

/**
 * Why a head was refused. [NOT_PROCESSED] is again the zero value; [NO_REASON] is reserved for a
 * [PathStatus.VALID] head, so a failure can never present "no reason" as its cause.
 */
enum class PathReason {
    NOT_PROCESSED,
    NO_REASON,
    NO_GEOMETRIC_CAUSE,
    SEGMENT_INTERSECTS_OBSTACLE,
    CLEARANCE_VIOLATION,
    TANGENT_DISCONTINUITY,
    CURVATURE_VIOLATION,
    UNCERTIFIED_MINIMUM,
    ENDPOINT_INSIDE_OBSTACLE,
    DEGENERATE_SEGMENT,
    DEGENERATE_OBSTACLE,
    NON_FINITE_GEOMETRY,
    OPERAND_OUT_OF_BOUND,
    REST_LENGTH_INVALID,
    UNSUPPORTED_GEOMETRY,
    BUFFER_CAPACITY
}

/**
 * What the construction loop did, kept apart from the geometric cause: an exhausted search is not an
 * intersection, and a guard that refused before a pass ran is not one that aborted inside it.
 */
enum class SearchOutcome {
    NOT_PROCESSED,
    CLEARED,
    SEARCH_EXHAUSTED,
    PASS_CAP,
    REJECTED_BEFORE_SEARCH,
    REJECTED_IN_SEARCH
}

/** One head's identity: the only key a consumer may use for a path. */
data class MuscleHeadId(val muscleId: String, val side: MuscleSide, val headIndex: Int)

/** One renderable identity: a two-head muscle is one instance per side, not one per head. */
data class MuscleInstanceId(val muscleId: String, val side: MuscleSide)

/**
 * One frame of derived anatomy: parallel arrays indexed by head slot, reused by every solve.
 *
 * The arrays are frame-owned scratch, rewritten by the next solve: read a path through
 * [MuscleState.path] or [pathTo], and copy any number you need to keep.
 */
class AnatomyFrame(
    headIds: List<MuscleHeadId>,
    muscles: Map<String, MuscleDefinition>,
    val pointCapacity: Int,
    landmarkCount: Int
) {

    init {
        require(pointCapacity >= 2) { "pointCapacity $pointCapacity cannot hold a path" }
        require(landmarkCount >= 0) { "landmarkCount $landmarkCount cannot be negative" }
        require(headIds.isNotEmpty()) { "a frame with no head has nothing to solve" }
        require(headIds.toSet().size == headIds.size) { "duplicate head in $headIds" }
        for (id in headIds) {
            val muscle = muscles[id.muscleId] ?: throw IllegalArgumentException("head $id names no muscle")
            require(id.headIndex in muscle.heads.indices) { "head $id is outside ${muscle.id}'s ${muscle.heads.size} heads" }
        }
    }

    /** Head slots, in the order the caller builds them: grouped by muscle, then by side. */
    val heads: List<MuscleHeadId> = headIds.toList()

    val headCount: Int get() = heads.size

    /** Floats per head: the `3 · pointCapacity` stride every point access is derived from. */
    val pointStride: Int = 3 * pointCapacity

    /** Renderable identities in first-appearance order; one entry per `(muscleId, side)`. */
    val instances: List<MuscleInstanceId> =
        heads.map { MuscleInstanceId(it.muscleId, it.side) }.distinct()

    val instanceCount: Int get() = instances.size

    private val instanceByHead: IntArray = IntArray(headCount) { slot ->
        instances.indexOf(MuscleInstanceId(heads[slot].muscleId, heads[slot].side))
    }

    val modelledPathLengths = FloatArray(headCount)
    val restLengths = FloatArray(headCount)
    val visualTendonPortionLengths = FloatArray(headCount)
    val relativePathLengthChanges = FloatArray(headCount)

    /** [PathStatus] ordinals; [PathReason] ordinals; [SearchOutcome] ordinals. */
    val statuses = IntArray(headCount) { PathStatus.NOT_PROCESSED.ordinal }
    val reasonCodes = IntArray(headCount) { PathReason.NOT_PROCESSED.ordinal }
    val searchOutcomes = IntArray(headCount) { SearchOutcome.NOT_PROCESSED.ordinal }

    val failingSegments = IntArray(headCount) { NO_INDEX }
    val failingObstacles = IntArray(headCount) { NO_INDEX }
    val pointCounts = IntArray(headCount)

    /** Visibility per instance: the conjunction of that instance's head statuses. */
    val renderable = BooleanArray(instances.size)

    /** Path points, engine units: `slot · pointStride + point · 3 + {x, y, z}`. */
    val paths = FloatArray(headCount * pointStride)

    /** Landmarks, engine units: `landmark · 3 + {x, y, z}`. */
    val landmarks = FloatArray(landmarkCount * 3)

    /** One view per head, built here: reading a state or a path allocates nothing. */
    val muscleStates: List<MuscleState> = heads.mapIndexed { slot, id ->
        MuscleState(this, slot, muscles.getValue(id.muscleId))
    }

    fun instanceOf(slot: Int): Int = instanceByHead[slot]

    /**
     * Appends one point to [slot]'s path. A full slot is refused — never grown and never truncated —
     * and marked [PathReason.BUFFER_CAPACITY]; whether that refusal happened inside a pass is the
     * solver's to report, so the search outcome is left alone here.
     */
    fun writePoint(slot: Int, x: Float, y: Float, z: Float): Boolean {
        val count = pointCounts[slot]
        if (count >= pointCapacity) {
            statuses[slot] = PathStatus.INVALID.ordinal
            reasonCodes[slot] = PathReason.BUFFER_CAPACITY.ordinal
            return false
        }
        val base = slot * pointStride + count * 3
        paths[base] = x
        paths[base + 1] = y
        paths[base + 2] = z
        pointCounts[slot] = count + 1
        return true
    }

    /** Raw point component at the derived stride, bounds unchecked: the layout accessor. */
    fun pointComponent(slot: Int, index: Int, component: Int): Float =
        paths[slot * pointStride + index * 3 + component]

    /** Copies point [index] of [slot]'s path into [out]; bounds-checked against the point count. */
    fun pathTo(slot: Int, index: Int, out: Vector3): Vector3 {
        val count = pointCounts[slot]
        if (index < 0 || index >= count) {
            throw IndexOutOfBoundsException("point $index outside 0..${count - 1} for ${heads[slot]}")
        }
        val base = slot * pointStride + index * 3
        return out.set(paths[base], paths[base + 1], paths[base + 2])
    }

    /**
     * Returns every slot to "no result": status, reason and outcome `NOT_PROCESSED`, counts zero,
     * indices and lengths cleared, nothing renderable. Path and landmark buffers are zeroed too, so a
     * stale point can never be read as this frame's anatomy.
     */
    fun clear() {
        for (slot in 0 until headCount) {
            statuses[slot] = PathStatus.NOT_PROCESSED.ordinal
            reasonCodes[slot] = PathReason.NOT_PROCESSED.ordinal
            searchOutcomes[slot] = SearchOutcome.NOT_PROCESSED.ordinal
            failingSegments[slot] = NO_INDEX
            failingObstacles[slot] = NO_INDEX
            pointCounts[slot] = 0
            modelledPathLengths[slot] = 0f
            restLengths[slot] = 0f
            visualTendonPortionLengths[slot] = 0f
            relativePathLengthChanges[slot] = 0f
        }
        paths.fill(0f)
        landmarks.fill(0f)
        renderable.fill(false)
    }

    companion object {
        /** The canonical frame for a definition: one slot per head per side, sized for that version. */
        fun of(definition: AnatomyDefinition): AnatomyFrame {
            val headIds = ArrayList<MuscleHeadId>(definition.headCount)
            for (muscle in definition.muscles) {
                for (side in MuscleSide.entries) {
                    for (headIndex in muscle.heads.indices) {
                        headIds += MuscleHeadId(muscle.id, side, headIndex)
                    }
                }
            }
            return AnatomyFrame(
                headIds = headIds,
                muscles = definition.muscles.associateBy { it.id },
                pointCapacity = definition.sampling.maxPointsPerHead,
                landmarkCount = definition.landmarks.size
            )
        }
    }
}

/** A zero-allocation read of one head's path: `size` is the current point count, not the capacity. */
class PathView internal constructor(private val frame: AnatomyFrame, private val slot: Int) {

    val size: Int get() = frame.pointCounts[slot]

    fun get(index: Int, out: Vector3): Vector3 = frame.pathTo(slot, index, out)
}

/**
 * A read-only window on one head's slot. Identity and metadata come from the definition; every
 * per-frame value is a getter into [AnatomyFrame], so a state never owns a value of its own.
 */
class MuscleState internal constructor(
    private val frame: AnatomyFrame,
    private val slot: Int,
    private val definition: MuscleDefinition
) {

    private val pathView = PathView(frame, slot)

    val id: String get() = definition.id
    val name: String get() = definition.name
    val side: MuscleSide get() = frame.heads[slot].side
    val headIndex: Int get() = frame.heads[slot].headIndex
    val headCount: Int get() = definition.heads.size
    val headLabel: String get() = definition.heads[headIndex].label
    val layer: MuscleLayer get() = definition.layer

    /** Whether the whole instance draws: a hidden instance hides every head of it. */
    val renderable: Boolean get() = frame.renderable[frame.instanceOf(slot)]

    val pointCount: Int get() = frame.pointCounts[slot]
    val modelledPathLength: Float get() = frame.modelledPathLengths[slot]
    val restLength: Float get() = frame.restLengths[slot]
    val visualTendonPortionLength: Float get() = frame.visualTendonPortionLengths[slot]
    val relativePathLengthChange: Float get() = frame.relativePathLengthChanges[slot]
    val pathStatus: PathStatus get() = PathStatus.entries[frame.statuses[slot]]
    val reason: PathReason get() = PathReason.entries[frame.reasonCodes[slot]]
    val searchOutcome: SearchOutcome get() = SearchOutcome.entries[frame.searchOutcomes[slot]]
    val failingSegment: Int get() = frame.failingSegments[slot]
    val failingObstacle: Int get() = frame.failingObstacles[slot]

    /** Copies point [index] into [out]; bounds-checked against [pointCount]. */
    fun pathPoint(index: Int, out: Vector3): Vector3 = pathView.get(index, out)

    /** This head's whole path, valid until the next solve. */
    val path: PathView get() = pathView
}
