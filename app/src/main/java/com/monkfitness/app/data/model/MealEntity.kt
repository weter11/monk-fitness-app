package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meals",
    foreignKeys = [
        ForeignKey(
            entity = MealCycle::class,
            parentColumns = ["id"],
            childColumns = ["cycleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cycleId"), Index(value = ["cycleId", "programDay", "mealTypeKey"], unique = true)]
)
data class MealEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cycleId: Long,
    val dayNumber: Int,
    val programDay: Int,
    val week: Int,
    val dayType: String,
    val mealTypeKey: String,
    val mealProfile: String,
    val templateId: String,
    val ingredientData: String,
    val calories: Int,
    val proteinGrams: Int,
    val optional: Boolean
)
