package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId

/**
 * What one Progress aggregation is about. The second context is deliberately not a Program: it is an
 * aggregation view, not an entity, so it carries no `ProgramId`, mints none and is stored nowhere. A
 * value of [AllPrograms] is a request to aggregate every Program's facts and nothing else — the facts
 * keep their own program identity, every total is a sum of Program-scoped facts, and measures that
 * cannot be summed without inventing a meaning (the streak) stay per-Program inside the aggregate.
 *
 * Every aggregation carries its scope, because one with no explicit scope is a number nobody can read:
 * "completed 12" means one thing for a Program and another for the aggregate.
 */
sealed interface ProgressScope {

    /** One Program's own facts — the default context, `selectedProgramId`. */
    data class OfProgram(val programId: ProgramId) : ProgressScope

    /**
     * Every Program's facts, aggregated. A `data object` with no members on purpose: there is nothing to
     * identify, name, save, select or delete, which is what "an aggregation view, not an entity" means.
     */
    data object AllPrograms : ProgressScope
}
