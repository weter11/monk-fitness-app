package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId

/**
 * What one Progress aggregation is *about* (§21).
 *
 * §21 gives Progress two contexts and is careful about what the second one is:
 *
 * ```text
 * default context: selectedProgramId
 * selector also offers: All Programs
 * ```
 *
 * The first is a Program, and this type names it. The second is deliberately **not** a Program: it is an
 * *aggregation view, not an entity*, so it carries no `ProgramId`, mints none, is stored nowhere and
 * appears in no table. A value of [AllPrograms] is a **request to aggregate the facts of every Program**
 * and nothing else — the facts it aggregates keep their own program identity, every total it produces is
 * a sum of Program-scoped facts, and the per-Program measures that cannot be summed without inventing a
 * meaning (the streak, §21) stay per-Program inside the aggregate.
 *
 * The scope is carried by every aggregation this layer produces ([CalendarProgress.scope],
 * [TrainingProgress.scope], [ProgressFacts.scope]) because an aggregation with no explicit scope is a
 * number nobody can read: "completed 12" means one thing for a Program and another for the aggregate.
 */
sealed interface ProgressScope {

    /** One Program's own facts — §21's default context, `selectedProgramId`. */
    data class OfProgram(val programId: ProgramId) : ProgressScope

    /**
     * Every Program's facts, aggregated.
     *
     * It is a `data object` with no members on purpose: there is nothing here to identify, name, save,
     * select or delete, which is what *"an aggregation view, not an entity"* means mechanically.
     */
    data object AllPrograms : ProgressScope
}
