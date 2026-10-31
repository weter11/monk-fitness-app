package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.prescription.Prescription

/**
 * One variant of one exercise family at the position its own hierarchy gives it. [level] is an ordinal
 * **inside this family and nowhere else** — no universal difficulty mathematics makes levels comparable
 * across families.
 *
 * [prescription] is required rather than optional because the stage's whole output is a presentation: an
 * optional one would leave "progress to the next variant" half representable, and the engine would have
 * to claim a harder variant prescribes exactly what an easier one did.
 */
data class ProgramProgressionVariant(
    val level: Int,
    val exerciseId: String,
    val prescription: Prescription
) {

    init {
        require(exerciseId.isNotBlank()) { "a progression variant must name its exercise" }
        require(prescription.setCount >= 1) {
            "a progression variant must prescribe at least one set: $exerciseId"
        }
    }

    val identity: String
        get() = "$level:$exerciseId"
}

/**
 * One family's declared progression hierarchy: which variants exist, at which positions, and therefore
 * exactly which moves are adaptations. The ladder is a **value the caller supplies** — the domain owns no
 * catalogue.
 *
 * The levels are contiguous, so "the next rung up" is always either declared or genuinely at the ceiling.
 * A level may declare more than one variant, which is the only shape in which a *variant* change is a
 * bounded change; it is also why a step is only ordered when the neighbouring level declares exactly one,
 * since with two the relation itself does not say which one to move to.
 *
 * The variants are held in canonical order, so two relations declaring the same ladder are the same value
 * whatever order the caller built them in.
 */
data class ProgramProgressionRelation(
    val familyId: String,
    val variants: List<ProgramProgressionVariant>
) {

    init {
        require(familyId.isNotBlank()) { "a progression relation must name its family" }
        require(variants.isNotEmpty()) {
            "a family with no declared variant has no progression hierarchy at all, so it has no " +
                "relation: $familyId"
        }
        val duplicates = variants.map { it.exerciseId }.groupingBy { it }.eachCount().filter { it.value > 1 }
        require(duplicates.isEmpty()) {
            "an exercise is one position in its family's hierarchy, found twice: ${duplicates.keys}"
        }
        require(variants == canonical(variants)) {
            "a relation's variants are held in level then exercise-id order, found " +
                "${variants.map { it.identity }}"
        }
        val declaredLevels = variants.map { it.level }.distinct()
        require(declaredLevels == (declaredLevels.first()..declaredLevels.last()).toList()) {
            "a family's level positions are contiguous, found $declaredLevels: a hole would leave a " +
                "step the relation does not declare"
        }
    }

    val levels: IntRange
        get() = variants.first().level..variants.last().level

    val lowestLevel: Int
        get() = levels.first

    val highestLevel: Int
        get() = levels.last

    fun variantsAt(level: Int): List<ProgramProgressionVariant> =
        variants.filter { it.level == level }

    fun declared(exerciseId: String): ProgramProgressionVariant? =
        variants.firstOrNull { it.exerciseId == exerciseId }

    /** `null` is the bounded non-progressing answer, never a substituted exercise. */
    fun stepUp(level: Int): ProgramProgressionVariant? =
        if (level >= highestLevel) null else variantsAt(level + 1).singleOrNull()

    fun stepDown(level: Int): ProgramProgressionVariant? =
        if (level <= lowestLevel) null else variantsAt(level - 1).singleOrNull()

    /**
     * The only exercises a *variant* change may reach: same level and declared, so the move is neither
     * harder nor easier. A swap to anything the family does not declare here is not an adaptation.
     */
    fun sameLevelVariants(variant: ProgramProgressionVariant): List<ProgramProgressionVariant> =
        variantsAt(variant.level).filter { it.exerciseId != variant.exerciseId }

    fun declares(level: Int): Boolean = variants.any { it.level == level }

    private companion object {

        fun canonical(variants: List<ProgramProgressionVariant>): List<ProgramProgressionVariant> =
            variants.sortedWith(compareBy({ it.level }, { it.exerciseId }))
    }
}
