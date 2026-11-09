package com.monkfitness.app.data.model

/**
 * The "In the app" card's three counts, derived from the exercise and nutrition registries.
 */
data class LibraryCounts(
    val animatedExercises: Int,
    val foods: Int,
    val dishes: Int
) {

    init {
        require(animatedExercises >= 0) { "a count of exercises is not negative, was $animatedExercises" }
        require(foods >= 0) { "a count of foods is not negative, was $foods" }
        require(dishes >= 0) { "a count of dishes is not negative, was $dishes" }
    }

    internal companion object {

        /** The derivation the card uses, over the catalogues themselves. */
        fun of(
            exercises: List<Exercise>,
            dedicatedAnimationIds: Set<String>,
            ingredients: List<NutritionIngredient>,
            templates: List<NutritionMealTemplate>
        ): LibraryCounts {
            val foodKeys = ingredients.map { it.key }.toSet()
            return LibraryCounts(
                animatedExercises = exercises.count { exercise ->
                    exercise.animationId.isNotBlank() && exercise.animationId in dedicatedAnimationIds
                },
                foods = ingredients.count { it.nameRes != 0 },
                dishes = templates.count { template ->
                    template.mainIngredientKeys.isNotEmpty() &&
                        template.mainIngredientKeys.all { key -> key in foodKeys }
                }
            )
        }
    }
}
