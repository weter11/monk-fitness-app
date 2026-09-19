package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.prescription.Prescription

/**
 * One variant of one exercise family, at the position the family's own progression hierarchy gives it
 * (§15: *"variant changes require explicit progression relations"*).
 *
 * [level] is an ordinal position **inside this family's hierarchy and nowhere else** — §15's ceiling
 * and floor are the family's own top and bottom rung, and the architecture defines no universal
 * difficulty mathematics that would make one family's level comparable to another's. [exerciseId] is
 * the exercise that position presents, and [prescription] is what that variant prescribes, both
 * supplied by the caller: the domain owns no family catalogue and no exercise metadata, so a relation
 * that invented either would be inventing the exercise library.
 *
 * Why the prescription is required rather than optional: the adaptive stage's whole output is a
 * *presentation* (`before` → `after` of one plan element), and a variant whose prescription nobody
 * states cannot be presented. Making it optional would leave "progress to the next variant" half
 * representable, and the engine would then have to keep the current prescription — i.e. claim that a
 * harder variant prescribes exactly what an easier one did. That claim is not the domain's to make.
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

    /** The variant's identity inside its family: its position and the exercise it presents. */
    val identity: String
        get() = "$level:$exerciseId"
}

/**
 * One family's declared progression hierarchy: which variants exist, at which positions, and therefore
 * exactly which moves are adaptations.
 *
 * This is the target architecture's answer to a question the blueprint leaves to the stage: §15
 * requires a variant change to *name an explicit progression relation*, and the target tree carries no
 * ladder. So the ladder is a **value the caller supplies** — never a constant in the domain, never the
 * pilot's `PilotProgressionProfiles`, which belongs to the Stage-1 generation and is deliberately not
 * reachable from here.
 *
 * Two structural facts do the work:
 *
 *  * **the levels are contiguous.** A family's positions run from its own lowest to its own highest
 *    with no hole, so "the next rung up" is always either declared or genuinely at the ceiling. A hole
 *    would make a step undefined in a way no policy could honestly resolve;
 *  * **a level may declare more than one variant.** Two variants at one position are two exercises the
 *    family declares to be at the same level — the only shape in which a *variant* change (§15) is a
 *    bounded change rather than a difficulty change. It is also why a step up is only ordered when the
 *    level above declares exactly one variant: with two declared, the relation itself does not say
 *    which one the family should move to, and the engine holds rather than picking one.
 *
 * The variants are held in a canonical order (level, then exercise id), so two relations that declare
 * the same ladder are the same value whatever order the caller built them in, and every read below is
 * a function of the value rather than of a caller's collection order.
 *
 * @property familyId the family this hierarchy belongs to. A relation is only usable for its own
 *   family: a resolution is meaningful only for the family whose ladder produced it.
 * @property variants the declared variants, ascending by level and then by exercise id.
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

    /** Every position this family declares, lowest first. */
    val levels: IntRange
        get() = variants.first().level..variants.last().level

    /** The family's bottom rung: the floor that stops a regression (§15). */
    val lowestLevel: Int
        get() = levels.first

    /** The family's top rung: the ceiling that stops a progression (§15). */
    val highestLevel: Int
        get() = levels.last

    /** The variants declared at [level], in exercise-id order. Empty when the level is not declared. */
    fun variantsAt(level: Int): List<ProgramProgressionVariant> =
        variants.filter { it.level == level }

    /** The variant [exerciseId] declares, or `null` when the family's hierarchy does not declare it. */
    fun declared(exerciseId: String): ProgramProgressionVariant? =
        variants.firstOrNull { it.exerciseId == exerciseId }

    /**
     * The single variant the family declares one level above [level], or `null` when there is none:
     * at the ceiling, when the level above declares several variants, or when [level] is not declared
     * at all. `null` is the bounded non-progressing answer §15 asks for — never a substitute exercise.
     */
    fun stepUp(level: Int): ProgramProgressionVariant? =
        if (level >= highestLevel) null else variantsAt(level + 1).singleOrNull()

    /**
     * The single variant the family declares one level below [level], or `null` when there is none:
     * at the floor, when the level below declares several variants, or when [level] is not declared.
     * The floor stops a regression here, exactly as the ceiling stops a progression.
     */
    fun stepDown(level: Int): ProgramProgressionVariant? =
        if (level <= lowestLevel) null else variantsAt(level - 1).singleOrNull()

    /**
     * The other variants the family declares at [variant]'s own position, in exercise-id order.
     *
     * These are the only exercises a *variant* change (§15) may reach: same level, declared, so the
     * move is neither harder nor easier, and the relation names both ends. A swap to anything the
     * family does not declare here is not an adaptation.
     */
    fun sameLevelVariants(variant: ProgramProgressionVariant): List<ProgramProgressionVariant> =
        variantsAt(variant.level).filter { it.exerciseId != variant.exerciseId }

    /** Whether [level] is a position this family declares. */
    fun declares(level: Int): Boolean = variants.any { it.level == level }

    private companion object {

        /** The canonical order: ascending level, then ascending exercise id. */
        fun canonical(variants: List<ProgramProgressionVariant>): List<ProgramProgressionVariant> =
            variants.sortedWith(compareBy({ it.level }, { it.exerciseId }))
    }
}
