package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramTargetProgramDayBindingEntity
import com.monkfitness.app.data.model.ProgramTargetScheduleRuleEntity

/**
 * Persistence for a revision's explicit target schedule source: its rules and its stated
 * `workoutId -> ProgramDayId` bindings.
 *
 * ### What this DAO can and cannot do
 *
 * Two inserts and two reads, and deliberately **no** `UPDATE` and **no** `DELETE`. A revision is
 * immutable (§6): a structural change saves a new revision rather than rewriting an old one, so a
 * stored rule or binding is written once and is never edited or dropped in place. A DAO that could
 * update would make "a revision's target semantics never change" a convention rather than a structural
 * fact, and a `DELETE` would let a revision keep its rules while quietly losing the bindings that
 * present them.
 *
 * ### The lookups are the revision, and the ordering is a read order
 *
 * Both reads are keyed on `revisionId` and nothing else. Neither falls back to a date, a revision
 * *number*, a plan day's position or name, a slot row or a legacy schedule column: those are facts
 * *about* a rule, never substitutes for which revision owns it.
 *
 * The two `ORDER BY` clauses are deterministic read orders over stored columns — `ruleId` for rules,
 * `workoutId` for bindings — so two reads of the same source are equality-identical and an unordered
 * insert order cannot leak into what a caller sees. They are not a canonicalisation of the caller's
 * own ordering: the adapter converts the rules in the order it is given them, and the *storage* order
 * is a read contract, not a decision about which rule matters.
 */
@Dao
interface ProgramTargetScheduleSourceDao {

    /** Stores the rules one revision states, each under its own `(revisionId, ruleId)`. */
    @Insert
    suspend fun insertRules(rules: List<ProgramTargetScheduleRuleEntity>)

    /** Stores the explicit bindings one revision states, each under its own `(revisionId, workoutId)`. */
    @Insert
    suspend fun insertBindings(bindings: List<ProgramTargetProgramDayBindingEntity>)

    /** Every stored rule of one revision, in stored rule-identity order. */
    @Query("SELECT * FROM `program_target_schedule_rule` WHERE `revisionId` = :revisionId ORDER BY `ruleId` ASC")
    suspend fun rulesOfRevision(revisionId: String): List<ProgramTargetScheduleRuleEntity>

    /** Every stored binding of one revision, in stored workout-identity order. */
    @Query("SELECT * FROM `program_target_program_day_binding` WHERE `revisionId` = :revisionId ORDER BY `workoutId` ASC")
    suspend fun bindingsOfRevision(revisionId: String): List<ProgramTargetProgramDayBindingEntity>
}
