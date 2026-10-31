package com.monkfitness.app.di

import java.util.UUID

/**
 * The only source of new identity: [newId] returns the **body** of an id, not one of the domain's typed
 * ids. `ProgramId`, `SlotId` and their siblings are inline value classes over a `String`, each its own
 * type, so the caller wraps the body in the one it creates (`ProgramId(idGenerator.newId())`) — that is
 * what keeps "a `SlotId` where a `ProgramId` belongs" a compile error. Identity is not derivation (two
 * ids for the same Program are the same Program), so nothing here needs determinism; minting is
 * injectable only so a test or an id-preserving import controls every id in a graph.
 */
fun interface IdGenerator {

    /**
     * One fresh id body, unique within this process. Callers must treat it as opaque: an identity,
     * never a value with a readable structure, an order or a creation time — nothing may parse it back.
     */
    fun newId(): String

    companion object {

        /** The production generator: a random UUID per call — unique without coordination, and
         * carrying no meaning that could be mistaken for data. */
        fun random(): IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
