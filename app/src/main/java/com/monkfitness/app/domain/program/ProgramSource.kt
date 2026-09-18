package com.monkfitness.app.domain.program

/**
 * Where a Program came from (§4, §5).
 *
 * Source is provenance, not behaviour: it says who authored the Program and therefore what the user
 * may do with it. The built-in Standard Program is the app's own — it can be selected, copied and
 * shared, but it cannot be edited or deleted directly, and editing it means copying it first. A
 * Program the user created or copied, and one created by importing a share file, are both the
 * user's own and carry no such restriction; import keeps its own value so that a UI can explain
 * where the Program came from.
 *
 * The values are persisted by name, so the order of this enum is not part of any contract.
 */
enum class ProgramSource {

    /** The built-in Standard Program shipped with the app. */
    STANDARD,

    /** A Program the user created in the editor, or copied from another Program. */
    USER,

    /** A Program created by importing a shared program file (§5). */
    IMPORTED;

    /** Whether this is the built-in program, whose copy-before-edit rule follows from it. */
    val isBuiltIn: Boolean
        get() = this == STANDARD
}
