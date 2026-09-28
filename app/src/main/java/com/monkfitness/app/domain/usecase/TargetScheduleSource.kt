package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding

/**
 * One immutable revision's **explicit** target scheduling source: the target rules it states and the
 * `workoutId -> ProgramDayId` bindings it states beside them.
 *
 * ### Why this value exists at all
 *
 * A target scheduling pass needs a [TargetScheduleInput], and its `scheduleDefinitions` have to be
 * caller-owned claims rather than something reconstructed. The blueprint forbids reconstructing them:
 * `ProgramSchedule` states *when legacy slots fall*, which is not a target rule, and a `ProgramDay`'s
 * `position` is an ordering while its `name` is a label — neither can stand in for a rule identity, a
 * workout identity, an anchor date or a derived rule's source. Until now there was therefore no
 * place at all from which an honest caller could state them, and every would-be production entry
 * point had to invent them.
 *
 * This value is that place, and it is owned by the **immutable revision**: §6 says a structural
 * change saves a new revision, so the rules a revision states are part of the saved plan and cannot
 * be edited in place. The membership is the revision identity and nothing else — no `programId`, no
 * revision number, no date, no list index.
 *
 * ### What it deliberately holds
 *
 * Exactly two things, and both are stated rather than derived:
 *
 *  * [rules] — each rule's `ruleId`, `workoutId`, cadence and anchor date, verbatim;
 *  * [programDayBindings] — one explicit `workoutId` to `ProgramDayId` statement per workout the
 *    revision presents, which is the mapping the occurrence presenter refuses to infer.
 *
 * It holds no repository, DAO, Room entity, clock, identity generator or UI state, and no execution,
 * session, performance, adaptive or slot state: a stored target source is *configuration*, and every
 * one of those is a fact about what happened afterwards.
 *
 * @property revisionId the immutable revision these rules and bindings belong to.
 * @property rules the explicitly stated target rules, in the revision's own deterministic order.
 * @property programDayBindings the explicitly stated workout-to-plan-day bindings.
 */
class TargetScheduleSource(
    revisionId: RevisionId,
    rules: List<TargetScheduleDefinition>,
    programDayBindings: List<TargetProgramDayBinding>
) {

    /** The immutable revision these rules and bindings belong to. */
    val revisionId: RevisionId = revisionId
    /** The rules are copied once, so a caller mutating its own list cannot rewrite this value. */
    val rules: List<TargetScheduleDefinition> = rules.toList()

    /** The bindings are copied once, on the same terms as [rules]. */
    val programDayBindings: List<TargetProgramDayBinding> = programDayBindings.toList()

    /**
     * Equality is field-by-field, so two reads of the same stored source are *equality-identical* —
     * which is what makes "the same revision read twice" a testable claim rather than a hope.
     *
     * It is written out rather than generated because the two collection properties are defensive
     * copies rather than constructor parameters, and a `data class` could not hold both.
     */
    override fun equals(other: Any?): Boolean = other is TargetScheduleSource &&
        revisionId == other.revisionId &&
        rules == other.rules &&
        programDayBindings == other.programDayBindings

    /** The hash of the same three fields [equals] compares. */
    override fun hashCode(): Int {
        var result = revisionId.hashCode()
        result = 31 * result + rules.hashCode()
        result = 31 * result + programDayBindings.hashCode()
        return result
    }

    /** The same three fields, spelled out — so a failure names what differed. */
    override fun toString(): String = "TargetScheduleSource(" +
        "revisionId=$revisionId, rules=$rules, programDayBindings=$programDayBindings)"
}

/**
 * What a read of one revision's explicit target source actually found.
 *
 * The three cases are kept apart on purpose. Collapsing *missing* into an empty [TargetScheduleSource]
 * would make "this revision states no target semantics" and "this revision states that it has none"
 * the same value, and only one of those is true — the second is not representable, because a
 * revision with no rules cannot produce an occurrence at all. An empty rule list is therefore never a
 * valid source, and the absence is reported instead of being invented away.
 *
 * [Malformed] is the third case: stored rows exist but do not describe a source this vocabulary can
 * read. That is invalid persisted data, and it is reported rather than defaulted — a cadence token
 * that is quietly replaced by a plausible one would change what the revision means.
 */
sealed interface TargetScheduleSourceRead {

    /** The revision states an explicit target source, and this is it. */
    data class Source(val source: TargetScheduleSource) : TargetScheduleSourceRead

    /** The revision states no target source at all — including a revision saved before this existed. */
    data class Missing(val revisionId: RevisionId) : TargetScheduleSourceRead

    /** Rows are stored for the revision, but they do not read back as a valid source. */
    data class Malformed(val revisionId: RevisionId, val reason: String) : TargetScheduleSourceRead
}

/**
 * Typed refusals for writing or reading one revision's explicit target source.
 *
 * Each is a *refusal* rather than a repair. Normalising a blank identity, merging two claims about one
 * rule, accepting a binding to another revision's plan day or overwriting a saved revision's source
 * would each be a scheduling decision made at a storage boundary, and §1 keeps ownership facts with
 * the value that states them.
 */
sealed class TargetScheduleSourceException(message: String) : IllegalArgumentException(message) {

    /** A stated rule claims an identity without naming it, so no occurrence could be attributed. */
    data class BlankRuleIdentity(val index: Int) : TargetScheduleSourceException(
        "target schedule source rule $index has a blank rule identity"
    )

    /** A stated rule names a rule but not the workout that rule produces. */
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
     * A binding names a `ProgramDayId` that is not one of the named revision's plan days.
     *
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
     * A saved revision already states a different source.
     *
     * §6 makes a revision immutable, so a second write is either the identical value (a no-op) or a
     * refusal. There is no merge and no update path.
     */
    data class ConflictingStoredSource(
        val revisionId: RevisionId,
        val storedSource: TargetScheduleSource,
        val requestedSource: TargetScheduleSource
    ) : TargetScheduleSourceException(
        "revision '${revisionId.value}' already states a different explicit target schedule source"
    )

    /** A read found stored rows that do not describe a readable source. */
    data class UnreadableStoredSource(
        val revisionId: RevisionId,
        val reason: String
    ) : TargetScheduleSourceException(
        "the explicit target schedule source stored for revision '${revisionId.value}' is not " +
            "readable: $reason"
    )
}
