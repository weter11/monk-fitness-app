package com.monkfitness.app

import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.data.model.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutGeneratorTest {
    private val generator = WorkoutGenerator()

    @Test
    fun testWeeklyStructure() {
        // Mon: Strength A, Tue: Mobility, Wed: Strength B, Thu: Rest, Fri: Functional, Sat: Mobility, Sun: Rest
        assertEquals(WorkoutType.STRENGTH_A, generator.generateWorkout(1).type)
        assertEquals(WorkoutType.MOBILITY, generator.generateWorkout(2).type)
        assertEquals(WorkoutType.STRENGTH_B, generator.generateWorkout(3).type)
        assertEquals(WorkoutType.REST, generator.generateWorkout(4).type)
        assertEquals(WorkoutType.FUNCTIONAL, generator.generateWorkout(5).type)
        assertEquals(WorkoutType.MOBILITY, generator.generateWorkout(6).type)
        assertEquals(WorkoutType.REST, generator.generateWorkout(7).type)
    }

    @Test
    fun testPhaseLogic() {
        val p1Exercise = generator.generateWorkout(1).exercises[0] // Pushups P1
        val p2Exercise = generator.generateWorkout(15).exercises[0] // Pushups P2 (Week 3)
        val p3Exercise = generator.generateWorkout(29).exercises[0] // Pushups P3 (Week 5)
        val p4Exercise = generator.generateWorkout(43).exercises[0] // Pushups P4 (Week 7)

        // Base sets 3, increase 1 per phase
        assertEquals(3, p1Exercise.sets)
        assertEquals(4, p2Exercise.sets)
        assertEquals(5, p3Exercise.sets)
        assertEquals(6, p4Exercise.sets)
    }
}
