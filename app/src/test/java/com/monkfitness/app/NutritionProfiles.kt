package com.monkfitness.app

import com.monkfitness.app.data.model.BiologicalSex
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.NutritionEnergyOutcome
import com.monkfitness.app.data.model.NutritionEnergyPlan
import com.monkfitness.app.data.model.NutritionProfile
import com.monkfitness.app.data.model.nutritionEnergyOutcome
import java.time.LocalDate

/**
 * The bodies the nutrition tests plan for. Every plan test names one, so a change to the energy model
 * shows up in the plans the same way it shows up for a user.
 */
object NutritionProfiles {

    val TODAY: LocalDate = LocalDate.of(2026, 11, 8)

    /** An adult man of [weightKg], aiming ten kilograms up unless a goal says otherwise. */
    fun man(
        weightKg: Int,
        heightCm: Int = 180,
        ageYears: Int = 30,
        goalWeightKg: Int = weightKg + 10
    ): NutritionProfile = NutritionProfile(weightKg, heightCm, ageYears, BiologicalSex.MALE, goalWeightKg)

    fun woman(
        weightKg: Int,
        heightCm: Int = 168,
        ageYears: Int = 30,
        goalWeightKg: Int = weightKg + 10
    ): NutritionProfile = NutritionProfile(weightKg, heightCm, ageYears, BiologicalSex.FEMALE, goalWeightKg)

    fun energy(
        profile: NutritionProfile,
        weighIns: List<BodyWeightEntry> = emptyList(),
        today: LocalDate = TODAY
    ): NutritionEnergyPlan = when (val outcome = nutritionEnergyOutcome(profile, weighIns, today)) {
        is NutritionEnergyOutcome.Ready -> outcome.plan
        else -> error("$profile cannot be planned for: $outcome")
    }
}
