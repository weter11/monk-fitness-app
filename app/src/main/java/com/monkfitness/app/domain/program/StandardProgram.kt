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
 * it, and the caller that will seed the Program's plan in a later stage.
 *
 * ### Why the id is stable and why the plan is not here
 *
 * The id is a fixed string rather than a generated one, because §3's fallback has to *find* this
 * Program after a delete — a generated id would make "select the Standard Program" a lookup with no
 * key. The plan is deliberately absent: deciding what the app's own program prescribes is the
 * editor/scheduler/Focus-Planner work of §30 steps 6–10, and this stage implements the *lifecycle*
 * contract only. [STANDARD_PROGRAM_PLAN_NOTE] records what a later stage has to provide and what this
 * stage assumes.
 *
 * ### What this stage does not do
 *
 * It does not seed the row. A Program that has no plan is not a representable state ([ProgramRevision]
 * requires a non-empty plan, and [ProgramRepository.createProgram] requires the Program to point at a
 * revision it creates), so seeding a Program without a plan would store an invalid graph. The
 * lifecycle layer therefore *requires* the Standard Program and reports it loudly when the delete
 * fallback finds it absent — see [ProgramLifecycleService]'s class docs — rather than inventing a
 * placeholder the architecture forbids.
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
 * What a later stage has to provide for the Standard Program, and what this stage assumes about it.
 *
 * Recorded instead of implemented, because the constraint is a contract the next stages must keep:
 * the Program must be created with a first revision that carries a real plan (§23, §27), and once it
 * is, this stage's rules apply to it unchanged — it is selectable, copyable and shareable, protected
 * from a direct edit and from deletion, and it is the fallback the selection moves to when the
 * selected Program is deleted.
 */
internal const val STANDARD_PROGRAM_PLAN_NOTE: String =
    "The Standard Program's plan is seeded by a later stage (§30 steps 6–10: the editor, the " +
        "scheduler and the Focus Planner). This stage requires only that the Program exists with the " +
        "id `standard-program`, a `STANDARD` source and a first revision carrying a real plan; the " +
        "lifecycle, selection, archive, copy and delete rules then apply to it unchanged."
