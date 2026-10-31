package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * The built-in **Standard Program** the app ships: its identity, and the rules that identity carries.
 *
 * It can be selected, copied and shared, but cannot be directly edited or deleted. Two of those are
 * enforced mechanically elsewhere: [ProgramLifecycleService] refuses an edit or a delete of this id, and
 * "editing Standard means creating a user copy first" is the same rule read as an operation —
 * [ProgramLifecycleService.copyProgram] on this id produces a user-owned Program the user may then edit
 * freely. This object's job is the identity itself, because three places need to agree on it and a constant
 * in one place keeps them from drifting: the delete fallback (deleting the selected Program selects the
 * Standard Program), the guard that protects it, and the production bootstrap that creates its plan.
 *
 * The product-owned plan is [com.monkfitness.app.domain.product.StandardProgramDefinition]: a MANUAL 30-day
 * program scheduled Monday/Tuesday/Thursday/Saturday with four plan days and 20 occurrences.
 * [com.monkfitness.app.bootstrap.StandardProgramBootstrap] creates it once, assigns the first bootstrap date
 * as `plannedStartDate`, and uses the existing Scheduler for opportunities — it does not depend on the legacy
 * generator or on generated/adaptive planning. The stable identity and lifecycle contract stay in this object,
 * so the lifecycle layer owns neither creation nor scheduling.
 */
object StandardProgram {

    /**
     * The stable identity of the built-in Standard Program.
     *
     * Fixed so the delete fallback can name it, and so the guard that protects it from a direct edit or
     * delete compares one id against one constant.
     */
    val programId: ProgramId = ProgramId("standard-program")

    /** The user-facing name of the built-in Program. */
    const val NAME: String = "Standard Program"

    /**
     * The one [ProgramSource] this id's rules are derived from. [ProgramSource.isBuiltIn] is the fact the
     * copy-before-edit guard reads, so a Program created with this source is protected whether or not its id
     * is the constant above.
     */
    val source: ProgramSource
        get() = ProgramSource.STANDARD
}