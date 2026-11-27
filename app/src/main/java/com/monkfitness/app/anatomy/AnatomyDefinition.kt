package com.monkfitness.app.anatomy

/**
 * The side an anatomical instance sits on: [A] is the `−Z` side the front leg and the A-arm share,
 * [P] its mirror. Not `animation.Side`, which names the side a rep advances.
 */
enum class MuscleSide {
    A,
    P;

    /** `+1` for [A] and `−1` for [P]: the lateral coordinate of an authored offset flips sides. */
    val lateralSign: Float get() = if (this == A) 1f else -1f

    /**
     * The segment instance this side's attachments pin to: arms `_A`/`_P`, legs `_F`/`_B`.
     *
     * Read from a table resolved once per side and segment: this runs per attachment per frame, and
     * scanning 23 candidates with a fresh suffix string each time is the stage's only steady-state
     * allocation.
     */
    fun instance(segment: Segment): SegmentId = SIDE_TABLE[segment.ordinal][ordinal]

    private fun claims(suffix: String?): Boolean = when (suffix) {
        null -> true
        "_A", "_F" -> this == A
        "_P", "_B" -> this == P
        else -> false
    }

    private companion object {
        val SIDE_TABLE: Array<Array<SegmentId>> = Array(Segment.entries.size) { segmentIndex ->
            val segment = Segment.entries[segmentIndex]
            Array(MuscleSide.entries.size) { sideIndex ->
                val side = MuscleSide.entries[sideIndex]
                SegmentId.entries.first { it.segment == segment && side.claims(it.suffix) }
            }
        }
    }
}

enum class MuscleLayer { SUPERFICIAL, DEEP }

/** Categorical only: there is no intensity and no numeric activation behind these values. */
enum class MuscleRole { PRIME_MOVER, SYNERGIST, STABILISER, ANTAGONIST, UNKNOWN }

/**
 * Which convention produced an attachment's numbers. [DERIVED_SEGMENT] is the only reviewable
 * answer; [UNRECORDED] is reported as frame-unverified and is never read as the verified one.
 */
enum class AttachmentFrame { DERIVED_SEGMENT, UNRECORDED }

/** A point in `segment`'s derived frame, in metres: `+Y` superior, `+X` anterior, [MuscleSide.A] on `−Z`. */
data class Attachment(
    val segment: Segment,
    val x: Float,
    val y: Float,
    val z: Float,
    val frame: AttachmentFrame = AttachmentFrame.UNRECORDED
)

/** One path. [label] is authored ("long head"), never a head number, and blank on a single-head muscle. */
data class MuscleHead(
    val origin: Attachment,
    val insertion: Attachment,
    val waypoints: List<Attachment> = emptyList(),
    val label: String = ""
) {
    /** Every attachment this head owns, in path order: the units the frame-provenance review closes. */
    val attachments: List<Attachment> get() = listOf(origin) + waypoints + insertion
}

/** Fractions of the solved path that render as tendon, not belly. */
data class TendonSpec(val originFraction: Float, val insertionFraction: Float)

/**
 * A reviewed exemption for one head, one obstacle and one interval. [envelopeSlackMetres] moves the
 * boundary off the first station at the margin, where no finite bracket can certify clearance.
 */
data class ContactExemption(
    val obstacleId: String,
    val headIndex: Int,
    val fromStation: Float,
    val toStation: Float,
    val envelopeSlackMetres: Float = 0f,
    val source: String
)

/**
 * An ellipsoid obstacle that follows [segment]'s derived frame. [global] puts it in every head's
 * union; a muscle that names a global obstacle is a definition error, not a redundant entry.
 */
data class WrapSurface(
    val id: String,
    val segment: Segment,
    val centreMetres: Triple<Float, Float, Float>,
    val radiiMetres: Triple<Float, Float, Float>,
    val global: Boolean = false
)

data class LandmarkDefinition(val id: String, val point: Attachment)

/** One muscle: one instance per side, one path per head, so a two-head muscle has four paths. */
data class MuscleDefinition(
    val id: String,
    val name: String,
    val layer: MuscleLayer,
    val heads: List<MuscleHead>,
    val widthMetres: Float,
    val thicknessMetres: Float,
    val tendon: TendonSpec,
    val wraps: List<String> = emptyList(),
    val flat: Boolean = false,
    val profile: Float = 0.5f,
    val radiusKnots: List<Pair<Float, Float>> = emptyList(),
    val passCap: Int = 8,
    val corridorMetres: Float = 0.12f,
    val contactExemptions: List<ContactExemption> = emptyList()
) {
    /** Tubes this muscle contributes: one per head per side. */
    val headSideCount: Int get() = heads.size * MuscleSide.entries.size
}

/** The resampling and buffer declaration a version fixes, so two runs of one version cannot disagree. */
data class Sampling(
    val spacingMetres: Float,
    val minSamples: Int,
    val maxSamples: Int,
    val maxPointsPerHead: Int
)

/**
 * Static, versioned anatomy: shared across characters and never written per frame. [epsNumMetres]
 * belongs to the version, because changing it invalidates every fixture signed against the margin.
 */
data class AnatomyDefinition(
    val id: String,
    val version: Int,
    val muscles: List<MuscleDefinition>,
    val wrapSurfaces: List<WrapSurface>,
    val landmarks: List<LandmarkDefinition>,
    val epsNumMetres: Float,
    val sampling: Sampling,
    /**
     * The largest direction change a path may carry at an interior sample before it is refused.
     *
     * The Stage-2 value is 2.6 rad (~150°): a single-waypoint detour around an ellipsoid folds
     * sharply at its apex, and a 45° bound refuses every such run instead of catching a real foldback.
     */
    val thetaMaxRadians: Float = 2.6f
) {
    private val musclesById: Map<String, MuscleDefinition> = muscles.associateBy { it.id }
    private val wrapsById: Map<String, WrapSurface> = wrapSurfaces.associateBy { it.id }

    /** Paths the frame carries: one per head per side, so twice the library's heads. */
    val headCount: Int get() = muscles.sumOf { it.headSideCount }

    /** Renderable identities: one per `(muscleId, side)`, so twice the library's muscles. */
    val instanceCount: Int get() = muscles.size * MuscleSide.entries.size

    fun muscle(id: String): MuscleDefinition? = musclesById[id]

    fun wrapSurface(id: String): WrapSurface? = wrapsById[id]

    /**
     * Every obstacle this head must clear, in definition order: the muscle's own list plus the global
     * ones, which are never named in that list.
     */
    fun union(muscle: MuscleDefinition): List<WrapSurface> {
        val named = muscle.wraps.mapNotNull { wrapsById[it] }
        return wrapSurfaces.filter { it.global } + named
    }
}
