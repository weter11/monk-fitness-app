package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * The built-in **Standard Program** the app ships (§4): its identity, and the rules that identity
 * carries.
 *
 * §4 is explicit about what this Program is for and what it is not:
 *
 * ```text
 * Built-in Standard Program:
 *     can be selected;
 *     can be copied;
 *     can be shared;
 *     cannot be directly edited;
 *     cannot be deleted.
 * ```
 *
 * Two of those are enforced mechanically elsewhere: [ProgramLifecycleService] refuses an edit or a
 * delete of this id, and "editing Standard means creating a user copy first" (§4) is the same rule
 * read as an operation — [ProgramLifecycleService.copyProgram] on this id produces a user-owned
 * Program the user may then edit freely. This object's job is the identity itself, because three
 * places need to agree on it and a constant in one place is what keeps them from drifting: the delete
 * fallback ("deleting the selected Program selects the Standard Program", §3), the guard that protects
 * it, and the production bootstrap that creates its explicit plan.
 *
 * ### The plan
 *
 * The product-owned plan is [com.monkfitness.app.domain.product.StandardProgramDefinition].
 * It is a MANUAL 30-day program scheduled Monday/Tuesday/Thursday/Saturday with four
 * plan days and 20 occurrences. [com.monkfitness.app.bootstrap.StandardProgramBootstrap]
 * creates this definition once, assigns the first bootstrap date as `plannedStartDate`,
 * and uses the existing Scheduler for opportunities. It does not depend on the legacy
 * generator or on generated/adaptive planning.
 *
 * ### What this stage does not do
 *
 * It does not choose the plan dynamically. The stable identity and lifecycle contract
 * remain in this object; the explicit product definition and production bootstrap are
 * separate collaborators so the lifecycle layer does not own creation or scheduling.
 */
object StandardProgram {

    /**
     * The stable identity of the built-in Standard Program.
     *
     * Fixed so §3's delete fallback can name it, and so the guard that protects it from a direct edit
     * or delete compares one id against one constant.
     */
    val programId: ProgramId = ProgramId("standard-program")

    /** The user-facing name of the built-in Program. */
    const val NAME: String = "Standard Program"

    /**
     * The one [ProgramSource] this id's rules are derived from. [ProgramSource.isBuiltIn] is the fact
     * the copy-before-edit guard reads, so a Program created with this source is protected whether or
     * not its id is the constant above.
     */
    val source: ProgramSource
        get() = ProgramSource.STANDARD
}

/**
 * The Standard Program is created by [com.monkfitness.app.bootstrap.StandardProgramBootstrap]
 * from the explicit product definition, including its first revision and scheduled opportunities.
 */