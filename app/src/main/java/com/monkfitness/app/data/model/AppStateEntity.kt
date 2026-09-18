package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The application's global runtime state, as far as the Program System owns it (§21, §23 `AppState`).
 *
 * It is a **single row** by construction: the primary key is pinned to [SINGLE_ROW_ID], so a second
 * state row is not representable and there is no "which row is the state" question anywhere in the
 * app. It is a global table, not a Program-owned one, which is why deleting a Program must not delete
 * it.
 *
 * Two references, and deliberately two different delete actions:
 *
 *  * [nextProgramId] — the blueprint's explicit `SET NULL` (§29). "Which program starts next" is a
 *    user choice about a program; if that program is deleted, the plan to start it next is void, so
 *    the reference is cleared rather than left dangling. Note what is *not* stored here: the
 *    auto-start flag is a separate fact from the selection, and its default is off.
 *  * [selectedProgramId] — `NO ACTION`, so the database **refuses** to delete the selected Program
 *    until the lifecycle has handled it. §3 is explicit that deleting the selected Program selects the
 *    Standard Program as the technical fallback, and archiving it requires choosing another one first.
 *    A `SET NULL` here would silently erase which program the user was in; the schema makes that
 *    impossible and leaves the choice to the layer that owns it.
 *
 * The selection is deliberately not a flag on [ProgramEntity]: selection is global runtime state, and
 * a Program that claimed to be selected could contradict this row (§21).
 *
 * @property id always [SINGLE_ROW_ID]; the row's only purpose as a key is to make a second one illegal.
 * @property selectedProgramId the Program the user is currently in, or `null` before one is selected.
 * @property nextProgramId the Program the user chose to start next, or `null` when none is chosen.
 * @property nextProgramAutoStart whether that next Program starts automatically. Auto-start is
 *   explicit (§3) and never resumes a paused program on its own; the default is `false`.
 */
@Entity(
    tableName = "app_state",
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["programId"],
            childColumns = ["selectedProgramId"],
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["programId"],
            childColumns = ["nextProgramId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("selectedProgramId"), Index("nextProgramId")]
)
data class AppStateEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val selectedProgramId: String? = null,
    val nextProgramId: String? = null,
    val nextProgramAutoStart: Boolean = false
) {

    init {
        require(id == SINGLE_ROW_ID) {
            "app_state is a single row; use SINGLE_ROW_ID ($SINGLE_ROW_ID), was $id"
        }
    }

    companion object {
        /** The only key the single state row may have. */
        const val SINGLE_ROW_ID: Int = 1
    }
}
