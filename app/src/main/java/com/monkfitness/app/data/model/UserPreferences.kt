package com.monkfitness.app.data.model

data class UserPreferences(
    val excludedFoods: Set<String> = emptySet(),
    val availableEquipment: Set<Equipment> = emptySet()
)
