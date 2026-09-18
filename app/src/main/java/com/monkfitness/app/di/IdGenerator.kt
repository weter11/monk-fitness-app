package com.monkfitness.app.di

import java.util.UUID

/**
 * The only source of new identity the Program System mints through — §26: "*ID generation is
 * injectable*", and §23: "*New domain IDs are stable typed IDs*".
 *
 * The persistence layer never mints an id: every id a repository writes arrives in the domain value
 * it is handed, so a test can drive a whole graph with ids it chose. This port is the production
 * replacement for that test-side choice — the composition root hands over the generator a later
 * stage's creation path will mint through, and the moment of minting stays visible at the call site
 * instead of hiding inside a repository or a mapper.
 *
 * ### What it returns, and what it deliberately does not
 *
 * [newId] returns the **body** of an id, not one of the domain's typed ids. `ProgramId`,
 * `RevisionId`, `SlotId` and their siblings are inline value classes over a `String`
 * (`domain/common`), each its own type so none is interchangeable with another; the caller wraps the
 * body in the one it is creating (`ProgramId(idGenerator.newId())`), which is what keeps "a
 * `SlotId` where a `ProgramId` belongs" a compile error. A generator that returned domain ids would
 * have to declare one method per id type — a second identity vocabulary beside the typed ids, and the
 * one place a wrong pairing could still be written by hand.
 *
 * ### Determinism
 *
 * §26's "*deterministic generation does not use uncontrolled `Random`*" is a rule about the layers
 * that *derive* something — a generated plan must be reproducible from its inputs (§20, §30 step 10),
 * and nothing in the target architecture may consult an uncontrolled random source to decide what a
 * user is shown. Identity is not derivation: two Programs with different ids are the same Program as
 * far as every rule in this blueprint is concerned. Minting is nevertheless injectable, so a test (or
 * a future import that must preserve the ids it was handed) supplies its own sequence and no id in a
 * graph is outside the test's control.
 */
fun interface IdGenerator {

    /**
     * One fresh id body, unique within this process.
     *
     * Callers must treat the result as opaque: it is an identity, never a value with a readable
     * structure, an order or a creation time, and nothing may parse it back.
     */
    fun newId(): String

    companion object {

        /**
         * The production generator: a random UUID per call.
         *
         * A UUID is unique without coordination and carries no meaning to be confused with data. The
         * rule above says what must *not* happen — this is not a seeded generator, because nothing
         * derives anything from identity: the plan generator is the layer that has to be
         * reproducible, and it does not mint ids to be reproducible.
         */
        fun random(): IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
