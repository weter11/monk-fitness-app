package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramTargetScheduleSourceDao
import com.monkfitness.app.data.mapper.toTargetProgramDayBinding
import com.monkfitness.app.data.mapper.toTargetProgramDayBindingEntity
import com.monkfitness.app.data.mapper.toTargetScheduleRule
import com.monkfitness.app.data.mapper.toTargetScheduleRuleEntity
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.usecase.TargetScheduleSource
import com.monkfitness.app.domain.usecase.TargetScheduleSourceException
import com.monkfitness.app.domain.usecase.TargetScheduleSourceRead

/**
 * One immutable revision's explicit target schedule source: stored once, read back losslessly, and
 * never invented.
 *
 * A revision's legacy `ProgramSchedule` says when its slots fall. That is not a target rule, so this
 * repository never reads it, never translates it and never mirrors a single one of its columns into a
 * target row. What it stores instead is what a caller states explicitly: each rule's own identity, the
 * workout it produces, its cadence *form*, that form's payload and its anchor date, plus one explicit
 * `workoutId -> ProgramDayId` binding per workout the revision presents.
 *
 * ### Three read outcomes, kept apart
 *
 * [sourceOf] returns [TargetScheduleSourceRead.Source], [TargetScheduleSourceRead.Missing] or
 * [TargetScheduleSourceRead.Malformed], and it does not collapse them. A revision with no rows is
 * *missing* — which is the honest reading of every revision saved before this existed, and of every
 * revision whose author never stated target semantics. Converting that into an empty source would say
 * "this revision states that it produces no occurrences", which is a different and stronger claim than
 * the truth. Invalid stored rows are *malformed* rather than defaulted to a plausible cadence, because
 * a default would change what the revision means.
 *
 * ### Immutability, and how it is enforced
 *
 * A revision's source is written once. [store] with the identical value writes nothing at all; [store]
 * with a *different* value for a revision that already has one is refused with
 * [TargetScheduleSourceException.ConflictingStoredSource], and the stored source survives untouched.
 * There is no update and no delete anywhere in this path, so "immutable after the revision is saved"
 * is a property of the code rather than a promise about it.
 *
 * ### Refusals that are claims, not repairs
 *
 * A blank rule or workout identity, two claims about one rule identity, two bindings about one
 * workout, and a binding naming a plan day that is not one of this revision's own days are each
 * refused with a typed reason. Each of them would otherwise be a scheduling decision made at a storage
 * boundary — which plan day does this workout present, whose rule wins — and §1 keeps ownership facts
 * with the value that states them.
 *
 * ### What this class deliberately does not do
 *
 * It consults no scheduler, planner, resolver, composer, policy, presenter, orchestrator,
 * `ProgramSchedule`, `ProgramDay`, slot or session, and it holds no clock and no identity generator:
 * nothing here reads the device's date, mints an id, chooses a window or a composition. It stores what
 * a caller decided and reads back what was stored.
 *
 * ### Atomicity
 *
 * A revision's rules and its bindings are one immutable unit — a source with rules but no bindings
 * cannot present an occurrence, and one with bindings but no rules states nothing at all — so both
 * writes execute inside one caller-supplied transaction. This repository never opens a transaction of
 * its own, because the layer that knows what else belongs in the same unit is above it.
 */
class TargetScheduleSourceRepository(
    private val sourceDao: ProgramTargetScheduleSourceDao,
    private val programDayDao: ProgramDayDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * Stores one revision's explicit target source, or refuses the write.
     *
     * The validations are claims about identity, not repairs: every rule names itself and its workout,
     * one rule claims each rule identity, one binding claims each workout, and every binding names a
     * plan day that belongs to *this* revision. Only then does anything reach the database, so a
     * refused source leaves no partial row behind.
     *
     * The check-then-write reads the stored source first, so an identical repeat touches no row and a
     * conflicting repeat throws before it writes anything.
     */
    suspend fun store(source: TargetScheduleSource) {
        validate(source)
        when (val stored = sourceOf(source.revisionId)) {
            is TargetScheduleSourceRead.Source ->
                if (stored.source == source) return else
                    throw TargetScheduleSourceException.ConflictingStoredSource(
                        revisionId = source.revisionId,
                        storedSource = stored.source,
                        requestedSource = source
                    )
            // A revision with no stored source has nothing to conflict with; the writes below are the
            // first ones for it.
            is TargetScheduleSourceRead.Missing -> Unit
            is TargetScheduleSourceRead.Malformed -> throw TargetScheduleSourceException
                .UnreadableStoredSource(source.revisionId, stored.reason)
        }

        inTransaction {
            sourceDao.insertRules(source.rules.map { it.toTargetScheduleRuleEntity(source.revisionId) })
            sourceDao.insertBindings(
                source.programDayBindings.map { it.toTargetProgramDayBindingEntity(source.revisionId) }
            )
        }
    }

    /**
     * The explicit target source one revision states, or the typed reason there is not one.
     *
     * Reconstructed from the stored rows and nothing else. The revision's `programSchedule` is not
     * consulted, no rule identity is derived from a plan day, a date or a weekday, and a revision with
     * no rows reads as [TargetScheduleSourceRead.Missing] rather than as an empty source.
     */
    suspend fun sourceOf(revisionId: RevisionId): TargetScheduleSourceRead {
        val ruleRows = sourceDao.rulesOfRevision(revisionId.value)
        val bindingRows = sourceDao.bindingsOfRevision(revisionId.value)
        if (ruleRows.isEmpty() && bindingRows.isEmpty()) {
            return TargetScheduleSourceRead.Missing(revisionId)
        }
        return try {
            TargetScheduleSourceRead.Source(
                TargetScheduleSource(
                    revisionId = revisionId,
                    rules = ruleRows.map { it.toTargetScheduleRule() },
                    programDayBindings = bindingRows.map { it.toTargetProgramDayBinding() }
                )
            )
        } catch (failure: IllegalArgumentException) {
            TargetScheduleSourceRead.Malformed(revisionId, failure.message ?: failure.toString())
        }
    }

    /**
     * The claims [store] refuses, checked before anything is written.
     *
     * The last one is the only check that reads another table: a binding's `ProgramDayId` must be one
     * of *this* revision's own plan days. A foreign key already refuses a day that does not exist; this
     * refuses a day that exists but belongs to a different revision, which would present one
     * revision's target schedule against another revision's plan.
     */
    private suspend fun validate(source: TargetScheduleSource) {
        val ruleIdentities = HashSet<String>()
        source.rules.forEachIndexed { index, rule ->
            if (rule.ruleId.isBlank()) {
                throw TargetScheduleSourceException.BlankRuleIdentity(index)
            }
            if (rule.workoutId.isBlank()) {
                throw TargetScheduleSourceException.BlankWorkoutIdentity(index)
            }
            if (!ruleIdentities.add(rule.ruleId)) {
                throw TargetScheduleSourceException.DuplicateRuleIdentity(rule.ruleId, index)
            }
        }

        val ownDays = programDayDao.daysOfRevision(source.revisionId.value)
            .map { day -> day.programDayId }
            .toSet()
        val boundWorkouts = HashSet<String>()
        source.programDayBindings.forEachIndexed { index, binding ->
            if (!boundWorkouts.add(binding.workoutId)) {
                throw TargetScheduleSourceException.DuplicateWorkoutBinding(binding.workoutId, index)
            }
            if (binding.programDayId.value !in ownDays) {
                throw TargetScheduleSourceException.ProgramDayOutsideRevision(
                    workoutId = binding.workoutId,
                    programDayId = binding.programDayId
                )
            }
        }
    }
}
