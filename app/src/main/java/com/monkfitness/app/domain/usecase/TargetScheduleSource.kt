package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding

/**
 * One immutable revision's **explicit** target scheduling source: the target rules it states and the
 * `workoutId -> ProgramDayId` bindings stated beside them.
 *
 * The rules have to be caller-owned claims rather than something reconstructed: `ProgramSchedule` states
 * *when legacy slots fall*, which is not a target rule, and a `ProgramDay`'s `position` is an ordering
 * while its `name` is a label — neither can stand in for a rule identity, a workout identity, an anchor
 * date or a derived rule's source.
 *
 * It is owned by the revision because a structural change saves a new revision, so the rules it states
 * are part of the saved plan and cannot be edited in place; membership is the revision identity and
 * nothing else. It holds no repository, clock or identity generator, and no execution, session,
 * performance, adaptive or slot state — a stored target source is *configuration*, and those are facts
 * about what happened afterwards.
 */
class TargetScheduleSource(
    revisionId: RevisionId,
    rules: List<TargetScheduleDefinition>,
    programDayBindings: List<TargetProgramDayBinding>
) {

    val revisionId: RevisionId = revisionId
    /** The rules are copied once, so a caller mutating its own list cannot rewrite this value. */
    val rules: List<TargetScheduleDefinition> = rules.toList()

    val programDayBindings: List<TargetProgramDayBinding> = programDayBindings.toList()

    /**
     * Equality is field-by-field, so two reads of the same stored source are *equality-identical*. Written
     * out because the collection properties are defensive copies, so a `data class` could not hold both.
     */
    override fun equals(other: Any?): Boolean = other is TargetScheduleSource &&
        revisionId == other.revisionId &&
        rules == other.rules &&
        programDayBindings == other.programDayBindings

    override fun hashCode(): Int {
        var result = revisionId.hashCode()
        result = 31 * result + rules.hashCode()
        result = 31 * result + programDayBindings.hashCode()
        return result
    }

    override fun toString(): String = "TargetScheduleSource(" +
        "revisionId=$revisionId, rules=$rules, programDayBindings=$programDayBindings)"
}

/**
 * What a read of one revision's explicit target source found, with the three cases kept apart. Collapsing
 * *missing* into an empty [TargetScheduleSource] would equate "states no target semantics" with "states
 * that it has none", and only the first exists — a revision with no rules cannot produce an occurrence.
 *
 * [Malformed] is invalid persisted data and is reported rather than defaulted: a cadence token quietly
 * replaced by a plausible one would change what the revision means.
 */
sealed interface TargetScheduleSourceRead {

    data class Source(val source: TargetScheduleSource) : TargetScheduleSourceRead

    /** The revision states no target source at all — including a revision saved before this existed. */
    data class Missing(val revisionId: RevisionId) : TargetScheduleSourceRead

    data class Malformed(val revisionId: RevisionId, val reason: String) : TargetScheduleSourceRead
}

/**
 * Typed refusals for writing or reading one revision's explicit target source; each is a *refusal* rather
 * than a repair, since normalising an identity, merging two claims or accepting a binding to another
 * revision's plan day would be a scheduling decision made at a storage boundary.
 */
sealed class TargetScheduleSourceException(message: String) : IllegalArgumentException(message) {

    data class BlankRuleIdentity(val index: Int) : TargetScheduleSourceException(
        "target schedule source rule $index has a blank rule identity"
    )

    data class BlankWorkoutIdentity(val index: Int) : TargetScheduleSourceException(
        "target schedule source rule $index has a blank workout identity"
    )

    /** Two stated rules claim one rule identity; a rule id is what the resolver, composer and a
     * derived cadence's source all key on. */
    data class DuplicateRuleIdentity(val ruleId: String, val index: Int) :
        TargetScheduleSourceException(
            "duplicate target schedule source rule identity '$ruleId' at rule $index"
        )

    /** Two bindings claim one workout identity, so which plan day it presents would be undecided. */
    data class DuplicateWorkoutBinding(val workoutId: String, val index: Int) :
        TargetScheduleSourceException(
            "duplicate target schedule source binding for workout '$workoutId' at binding $index"
        )

    /**
     * The day exists — a foreign key says so — but it belongs to some *other* revision, and a binding
     * across revisions would present one revision's schedule against another revision's plan.
     */
    data class ProgramDayOutsideRevision(
        val workoutId: String,
        val programDayId: ProgramDayId
    ) : TargetScheduleSourceException(
        "target schedule source binding for workout '$workoutId' names plan day " +
            "'${programDayId.value}', which is not a plan day of its own revision"
    )

    /**
     * A saved revision already states a different source. A revision is immutable, so a second write is
     * either the identical value (a no-op) or this refusal: no merge, no update path.
     */
    data class ConflictingStoredSource(
        val revisionId: RevisionId,
        val storedSource: TargetScheduleSource,
        val requestedSource: TargetScheduleSource
    ) : TargetScheduleSourceException(
        "revision '${revisionId.value}' already states a different explicit target schedule source"
    )

    data class UnreadableStoredSource(
        val revisionId: RevisionId,
        val reason: String
    ) : TargetScheduleSourceException(
        "the explicit target schedule source stored for revision '${revisionId.value}' is not " +
            "readable: $reason"
    )
}
