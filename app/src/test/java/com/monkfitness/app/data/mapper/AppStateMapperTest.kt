package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.AppStateEntity
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** The `app_state` row ⇄ `AppState`, and the single-row identity both directions keep. */
class AppStateMapperTest {

    @Test
    fun anEmptyStateRoundTripsAsAbsenceAndNotAsADefaultProgram() {
        val empty = AppState()

        val row = empty.toEntity()

        assertNull("nothing selected is a value, not a fallback (§3)", row.selectedProgramId)
        assertNull(row.nextProgramId)
        assertFalse("auto-start defaults to off and is not turned on here", row.nextProgramAutoStart)
        assertEquals(empty, row.toDomain())
    }

    @Test
    fun aSelectedAndANextProgramRoundTripAsTypedIds() {
        val state = AppState(
            selectedProgramId = ProgramId("program-a"),
            nextProgramId = ProgramId("program-b"),
            nextProgramAutoStart = true
        )

        val row = state.toEntity()

        assertEquals(AppState.SINGLE_ROW_ID, row.id)
        assertEquals("program-a", row.selectedProgramId)
        assertEquals("program-b", row.nextProgramId)
        assertEquals(state, row.toDomain())
    }

    @Test
    fun theSelectionIsGlobalStateAndNotAProgramProperty() {
        assertTrue(
            "the row carries the selection",
            fieldsOf(AppStateEntity::class.java)
                .containsAll(listOf("selectedProgramId", "nextProgramId", "nextProgramAutoStart"))
        )
        assertTrue(
            "and no Program claims to be selected (§21)",
            fieldsOf(Program::class.java).none { it.contains("select", ignoreCase = true) }
        )
    }

    @Test
    fun writingAlwaysUsesTheSingleRowIdentity() {
        val row = AppState(selectedProgramId = ProgramId("program-a")).toEntity()

        assertEquals(1, row.id)
        assertEquals(
            "the state is one row of four facts — a second state row is not representable (§23)",
            listOf("id", "selectedProgramId", "nextProgramId", "nextProgramAutoStart"),
            fieldsOf(AppStateEntity::class.java)
        )
    }

    /** The declared field names of a class, without the synthetic members Kotlin adds. */
    private fun fieldsOf(type: Class<*>) = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
        .map { it.name }
}
