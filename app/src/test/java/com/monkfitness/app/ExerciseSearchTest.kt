package com.monkfitness.app

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.util.matchesQuery
import com.monkfitness.app.util.normalize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSearchTest {

    private val exercise = Exercise(
        id = "cat_cow",
        nameRes = 0,
        descriptionRes = 0,
        techniqueRes = 0,
        imageRes = null,
        sets = 2,
        reps = 10,
        nameRu = "Кошка-корова",
        nameEn = "Cat Cow",
        descriptionRu = "Мягкая мобилизация позвоночника",
        descriptionEn = "Gentle spinal mobility flow"
    )

    @Test
    fun testNormalizeHandlesCaseAndYo() {
        assertTrue(normalize("  Ёлка  ") == "елка")
    }

    @Test
    fun testMatchesQuerySupportsRussianAndEnglish() {
        assertTrue(matchesQuery(exercise, "кошка"))
        assertTrue(matchesQuery(exercise, "spinal"))
        assertTrue(matchesQuery(exercise, "cow"))
        assertFalse(matchesQuery(exercise, "burpee"))
    }
}
