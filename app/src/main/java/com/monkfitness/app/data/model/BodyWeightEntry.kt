package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "body_weight_log",
    indices = [Index(value = ["date"], unique = true)]
)
data class BodyWeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weightKg: Float,
    val date: String
)
