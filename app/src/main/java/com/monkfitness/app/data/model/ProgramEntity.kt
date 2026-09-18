package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One saved Program, as the Program System persists it (§23 `Program`).
 *
 * The row holds the Program's **identity** and the facts that are not structure: what it is called,
 * where it came from, where it is in its life, which revision currently describes it, when it was
 * created and changed, when it is planned to start and when it actually did, and when it was
 * archived. Those are exactly the operations §6 lists as *not* creating a revision, which is why
 * none of them is revision content.
 *
 * Three things this row deliberately does **not** carry, each of which would be a second source of
 * truth somewhere else:
 *
 *  * **no mode** — a Program's mode is its current revision's mode; storing it here as well would let
 *    the two disagree;
 *  * **no plan** — days and prescriptions belong to the revision, and a revision is immutable;
 *  * **no selection flag** — which Program is selected is global runtime state (`app_state`, §21), so a
 *    Program never claims to be selected and the two can never contradict each other.
 *
 * A cycle number, a session date and a day number are never ownership identifiers (§1), so they are
 * not here either.
 *
 * `currentRevisionId` is a reference, not a foreign key, and that is deliberate: create/copy/import
 * produce a Program *together with* its first revision in one transaction (§27), and a mutual foreign
 * key between the two tables could not be satisfied by any insert order. Ownership runs the other
 * way — the revision carries the cascade — so deleting a Program deletes its revisions, while the
 * pointer is a plan fact the lifecycle updates.
 *
 * @property programId stable identity for the whole life of the Program.
 * @property name user-facing name; renaming is not a structural change.
 * @property description user-facing description; may be empty.
 * @property source provenance, a `ProgramSource` name (token column).
 * @property lifecycleStatus where it is in `NOT_STARTED / RUNNING / PAUSED / COMPLETED` (token column).
 *   Archiving is not a state: it is `archivedAt`.
 * @property currentRevisionId the revision that currently describes the plan.
 * @property createdAt when the Program was created, in epoch milliseconds.
 * @property updatedAt when it was last changed in any way, in epoch milliseconds.
 * @property plannedStartDate the date it is planned to start on, `YYYY-MM-DD`; a plan, not a fact.
 * @property actualStartDate when it actually started, in epoch milliseconds; a fact, set by starting it.
 * @property archivedAt when it was archived, in epoch milliseconds, or `null` while it is not.
 */
@Entity(tableName = "program")
data class ProgramEntity(
    @PrimaryKey val programId: String,
    val name: String,
    val description: String,
    val source: String,
    val lifecycleStatus: String,
    val currentRevisionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val plannedStartDate: String? = null,
    val actualStartDate: Long? = null,
    val archivedAt: Long? = null
)
