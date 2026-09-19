package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.program.SchedulerFixture
import java.time.LocalDate

/**
 * The transfer suites' own shapes: the moment an import happens at, the real exercise ids the fixtures plan
 * with, and the documents the schema and validation suites read.
 *
 * ### Why the documents are written by hand
 *
 * A reader tested only against its own writer's output is a reader tested against one whitespace and one
 * field order — and the two are supposed to be independent, which is what [ProgramTransferJsonTest] proves
 * from the other side. So the fixture writes its documents itself, and it is *compact on purpose*: the
 * focus configuration and the plan are single-line fragments ([CUSTOM_FOCUS], [PLAN]) that a suite can
 * replace whole or edit inside, which is how each rejection test below states exactly one thing wrong with
 * one document.
 *
 * ### What it deliberately is not
 *
 * Not a *valid* program in the domain's sense — [CUSTOM_FOCUS] and [PLAN] happen to be one, and
 * [PLAN_WITH_A_WORK_DAY_THAT_PLANS_NOTHING] deliberately is not, because "a work day must plan something" is
 * the domain's own rule (§6 asks for it to be reused rather than restated) and the transfer suite has to be
 * able to hand the draft that violates it to the *domain's* validation to prove the reuse.
 */
internal object ProgramTransferFixture {

    /** A Monday: the fixture schedules Monday/Wednesday/Friday, so an import has a first date. */
    val IMPORTED_ON: LocalDate = LocalDate.parse("2026-09-21")

    /** The moment the import happens at, in the scheduler fixture's own zone. */
    fun importedAt() = SchedulerFixture.at(IMPORTED_ON)

    /**
     * Exercise ids taken from the app's **real** catalogue (`WorkoutGenerator`), so the documents below
     * describe plans the app can actually hold — and so the architecture suite can prove the production
     * exerciseId boundary still knows them.
     */
    val KNOWN_EXERCISE_IDS: Set<String> = setOf("pushups", "pike_pushups", "plank", "face_pull", "squats")

    // ------------------------------------------------------------------ the fragments

    /** §8's `CUSTOM`: every focus's share, in the vocabulary's own order, summing to 100%. */
    val CUSTOM_FOCUS: String =
        """{ "goal": "CUSTOM", "allocations": [ { "focus": "PUSH", "percent": 60 }, """ +
            """{ "focus": "LEGS", "percent": 40 } ] }"""

    /** §8's `CUSTOM` with its shares out of the vocabulary's canonical order. */
    val CUSTOM_FOCUS_OUT_OF_ORDER: String =
        """{ "goal": "CUSTOM", "allocations": [ { "focus": "LEGS", "percent": 40 }, """ +
            """{ "focus": "PUSH", "percent": 60 } ] }"""

    /** §8's `FOCUSED`: the focuses the plan is built around, no share stated. */
    val FOCUSED_FOCUS: String = """{ "goal": "FOCUSED", "focuses": ["PUSH", "LEGS"] }"""

    /** §8's `BALANCED`: the whole vocabulary, nothing stated. */
    val BALANCED_FOCUS: String = """{ "goal": "BALANCED" }"""

    /**
     * The three-day plan: a training day with an exercise used **twice** (two occurrences, per-set
     * prescriptions, one generated and one pinned user-authored element), a rest day, and a mobility day
     * with a time prescription.
     */
    val PLAN: String =
        """[ """ +
            """{ "type": "TRAINING", "name": "Push day", "exercises": [ """ +
            """{ "exerciseId": "pushups", "prescription": { "dimension": "REP_BASED", "perSetTargets": [12, 10, 8, 6] }, "origin": "GENERATED", "pinned": false }, """ +
            """{ "exerciseId": "pushups", "prescription": { "dimension": "REP_BASED", "perSetTargets": [12, 10, 8, 6] }, "origin": "GENERATED", "pinned": false }, """ +
            """{ "exerciseId": "pike_pushups", "prescription": { "dimension": "REP_BASED", "perSetTargets": [8, 8] }, "origin": "USER_AUTHORED", "pinned": true } """ +
            """] }, """ +
            """{ "type": "REST", "exercises": [] }, """ +
            """{ "type": "MOBILITY", "name": "Mobility", "exercises": [ """ +
            """{ "exerciseId": "plank", "prescription": { "dimension": "TIME_BASED", "perSetTargets": [30, 30, 45] }, "origin": "USER_AUTHORED", "pinned": false } """ +
            """] } """ +
            """]"""

    /**
     * [PLAN] with its mobility day planning nothing.
     *
     * §20's rule has two halves, and this is the half the *domain* owns: a day that is not a rest day must
     * prescribe work, which `ProgramDraftValidation` states. The fixture carries the document so a suite can
     * prove the transfer layer hands that draft to the domain rather than re-implementing the rule.
     */
    val PLAN_WITH_A_WORK_DAY_THAT_PLANS_NOTHING: String =
        PLAN.replace(
            """{ "type": "MOBILITY", "name": "Mobility", "exercises": [ """ +
                """{ "exerciseId": "plank", "prescription": { "dimension": "TIME_BASED", "perSetTargets": [30, 30, 45] }, "origin": "USER_AUTHORED", "pinned": false } """ +
                """] }""",
            """{ "type": "MOBILITY", "name": "Mobility", "exercises": [] }"""
        )

    // ------------------------------------------------------------------ the documents

    /**
     * A whole document, with the two fragments a suite states differently.
     *
     * The fragments are interpolated at the start of their lines, so their own indentation is not re-flowed —
     * which is deliberate: the reader must not depend on formatting, and a document whose focus object sits
     * at column zero is one more proof of that.
     */
    fun document(focus: String = CUSTOM_FOCUS, days: String = PLAN): String = """
        {
          "format": "monkfitness.program",
          "formatVersion": 1,
          "program": {
            "name": "Imported strength",
            "description": "a program that arrived as a file"
          },
          "revision": {
            "mode": "MANUAL",
            "duration": { "kind": "FIXED_DAYS", "days": 30 },
            "schedule": { "kind": "FIXED_WEEKDAYS", "weekdays": ["MONDAY", "WEDNESDAY", "FRIDAY"] },
            "focus": $focus,
            "days": $days
          }
        }
    """.trimIndent()

    /** A document that passes every validation this stage has. */
    val VALID_DOCUMENT: String = document()

    /** [VALID_DOCUMENT] as the bytes a share would carry. */
    val VALID_BYTES: ByteArray
        get() = ProgramTransferFormat.encode(VALID_DOCUMENT)

    /**
     * [VALID_DOCUMENT] with one fragment replaced — the "hand-edited file" every rejection test states.
     *
     * It fails loudly when the fragment is not there or when the edit changed nothing, so a test cannot pass
     * by editing nothing.
     */
    fun edited(old: String, new: String, from: String = VALID_DOCUMENT): String {
        require(from.contains(old)) { "the fixture does not contain: $old" }
        val edited = from.replace(old, new)
        require(edited != from) { "editing changed nothing: $old -> $new" }
        return edited
    }

    /** [edited] as bytes. */
    fun editedBytes(old: String, new: String): ByteArray =
        ProgramTransferFormat.encode(edited(old, new))
}
