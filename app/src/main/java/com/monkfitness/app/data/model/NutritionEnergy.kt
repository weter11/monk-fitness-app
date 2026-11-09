package com.monkfitness.app.data.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The two sexes the resting-rate equation is fitted to. */
enum class BiologicalSex { MALE, FEMALE }

/**
 * What the energy model needs from the user. A null field is one the user has not given: the model never
 * stands a default in for it, because a made-up body produces a made-up target.
 */
data class NutritionProfile(
    val weightKg: Int? = null,
    val heightCm: Int? = null,
    val ageYears: Int? = null,
    val sex: BiologicalSex? = null,
    val goalWeightKg: Int? = null
)

enum class NutritionProfileField { WEIGHT, HEIGHT, AGE, GOAL_WEIGHT }

/** Why a typed value was refused: it is outside the range, or it is a body the model does not serve. */
enum class NutritionProfileError { OUT_OF_RANGE, ADULTS_ONLY }

val NUTRITION_WEIGHT_RANGE = 35..220
val NUTRITION_HEIGHT_RANGE = 120..230
val NUTRITION_AGE_RANGE = 18..90
val NUTRITION_GOAL_WEIGHT_RANGE = 35..220

private fun NutritionProfileField.range(): IntRange = when (this) {
    NutritionProfileField.WEIGHT -> NUTRITION_WEIGHT_RANGE
    NutritionProfileField.HEIGHT -> NUTRITION_HEIGHT_RANGE
    NutritionProfileField.AGE -> NUTRITION_AGE_RANGE
    NutritionProfileField.GOAL_WEIGHT -> NUTRITION_GOAL_WEIGHT_RANGE
}

/**
 * Why [raw] cannot be stored in [field], or null when it can.
 *
 * A blank field is not an error: it is a value the user has not given yet, and the completeness card
 * speaks for it. Anything else must fall inside the field's range, and an age below the adult range is
 * refused with its own reason so the screen can say why rather than quote a bound.
 */
fun nutritionProfileError(field: NutritionProfileField, raw: String): NutritionProfileError? {
    if (raw.isBlank()) return null
    val value = raw.trim().toIntOrNull() ?: return NutritionProfileError.OUT_OF_RANGE
    if (field == NutritionProfileField.AGE && value < NUTRITION_AGE_RANGE.first) {
        return if (value > 0) NutritionProfileError.ADULTS_ONLY else NutritionProfileError.OUT_OF_RANGE
    }
    return if (value in field.range()) null else NutritionProfileError.OUT_OF_RANGE
}

/** A stored value read back as a usable one: anything the range refuses reads as "not given". */
private fun Int?.usableIn(range: IntRange): Int? = this?.takeIf { it in range }

/** Mifflin-St Jeor: the energy a body spends at rest, in kcal/day. */
fun nutritionBasalMetabolicRate(weightKg: Int, heightCm: Int, ageYears: Int, sex: BiologicalSex): Int {
    val resting = (10.0 * weightKg) + (6.25 * heightCm) - (5.0 * ageYears)
    val sexTerm = if (sex == BiologicalSex.MALE) 5.0 else -161.0
    return (resting + sexTerm).roundToInt()
}

/**
 * The multiple of the resting rate an ordinary day costs.
 *
 * 1.55 is the moderate band: three training days a week on top of a day that is otherwise lived
 * normally. The active Program's own weekly training count is not read here — the nutrition domain keeps
 * no Program table — so the factor is stated as one constant instead of being derived per user.
 */
const val NUTRITION_ACTIVITY_FACTOR = 1.55

fun nutritionMaintenanceCalories(basalMetabolicRate: Int): Int =
    (basalMetabolicRate * NUTRITION_ACTIVITY_FACTOR).roundToInt()

/** The surplus a full gain phase adds to the weekly average, in kcal/day. */
const val NUTRITION_GAIN_SURPLUS_KCAL = 400

/** The last kilograms of a gain, over which the surplus falls linearly to nothing. */
const val NUTRITION_TAPER_BAND_KG = 3.0

/** The fastest weekly gain the plan feeds: half a percent of body weight. */
const val NUTRITION_WEEKLY_GAIN_LIMIT = 0.005

/** No day's target sits below this multiple of the resting rate. */
const val NUTRITION_CALORIE_FLOOR_BMR_FACTOR = 1.2

/** No day's target sits above this many kcal: past it a gain surplus stops being food. */
const val NUTRITION_CALORIE_CEILING_KCAL = 4500

const val NUTRITION_PROTEIN_GRAMS_PER_KG = 2.0
const val NUTRITION_PROTEIN_RANGE_GRAMS = 10
const val NUTRITION_PROTEIN_MIN_PER_KG = 1.6
const val NUTRITION_PROTEIN_MAX_PER_KG = 2.2

/**
 * How the week's surplus is spread over its day types.
 *
 * The planner's week is three training, two light and two rest days, and 3 x 1.30 + 2 x 1.00 + 2 x 0.55
 * is exactly 7: whatever surplus the phase asks for, seven days of these shares add up to it, and no
 * share is negative — so a rest day never dips under maintenance while the plan is gaining.
 */
private fun NutritionDayType.surplusShare(): Double = when (this) {
    NutritionDayType.TRAINING -> 1.30
    NutritionDayType.LIGHT -> 1.00
    NutritionDayType.REST -> 0.55
}

enum class NutritionEnergyPhase { GAIN, TAPER, MAINTAIN }

/** Which limit decided a day's calories, when one did. */
enum class NutritionCalorieLimit { FLOOR, CEILING }

enum class NutritionWeightSource { SEVEN_DAY_AVERAGE, LATEST_WEIGH_IN, SETTINGS }

data class NutritionResolvedWeight(val weightKg: Int, val source: NutritionWeightSource)

/** A day's targets, and the numbers behind them, as the targets card states them. */
data class NutritionDayEnergy(
    val phase: NutritionEnergyPhase,
    val basalMetabolicRate: Int,
    val maintenanceCalories: Int,
    val surplusCalories: Int,
    val currentWeightKg: Int,
    val goalWeightKg: Int,
    val weightSource: NutritionWeightSource,
    val limit: NutritionCalorieLimit?,
    val targets: NutritionTargets
)

/**
 * The energy model resolved for one body: the resting rate, the maintenance it costs, the phase the
 * weight is in and the surplus that phase feeds. Every day of a plan is priced from this one object, so
 * a generated day and the live target can never disagree about the body they were computed for.
 */
data class NutritionEnergyPlan(
    val basalMetabolicRate: Int,
    val maintenanceCalories: Int,
    val phase: NutritionEnergyPhase,
    /** The surplus over maintenance this phase feeds, as a weekly average in kcal/day. */
    val weeklySurplusKcal: Int,
    /** True when the weigh-ins held the surplus below what the phase asked for. */
    val heldByWeeklyGain: Boolean,
    val currentWeightKg: Int,
    val goalWeightKg: Int,
    val weightSource: NutritionWeightSource,
    val proteinMinGrams: Int,
    val proteinMaxGrams: Int
) {
    fun surplusFor(dayType: NutritionDayType): Int =
        (weeklySurplusKcal * dayType.surplusShare()).roundToInt()

    fun targetsFor(dayType: NutritionDayType): NutritionTargets {
        val daily = maintenanceCalories + surplusFor(dayType)
        val limited = limitNutritionCalories(daily, basalMetabolicRate)
        return NutritionTargets(
            dailyCalories = limited.first,
            proteinMinGrams = proteinMinGrams,
            proteinMaxGrams = proteinMaxGrams
        )
    }

    fun forDay(dayType: NutritionDayType): NutritionDayEnergy {
        val (calories, limit) = limitNutritionCalories(maintenanceCalories + surplusFor(dayType), basalMetabolicRate)
        return NutritionDayEnergy(
            phase = phase,
            basalMetabolicRate = basalMetabolicRate,
            maintenanceCalories = maintenanceCalories,
            surplusCalories = calories - maintenanceCalories,
            currentWeightKg = currentWeightKg,
            goalWeightKg = goalWeightKg,
            weightSource = weightSource,
            limit = limit,
            targets = NutritionTargets(calories, proteinMinGrams, proteinMaxGrams)
        )
    }
}

sealed interface NutritionEnergyOutcome {
    /** A field the model needs is missing; nothing is generated until it is given. */
    data object IncompleteProfile : NutritionEnergyOutcome

    /** The targets are fitted to adults; a body under 18 is told so instead of being planned for. */
    data object Underage : NutritionEnergyOutcome

    data class Ready(val plan: NutritionEnergyPlan) : NutritionEnergyOutcome
}

/** A day's calories held between the resting floor and the ceiling, and which one bound. */
fun limitNutritionCalories(calories: Int, basalMetabolicRate: Int): Pair<Int, NutritionCalorieLimit?> {
    val floor = ceil(basalMetabolicRate * NUTRITION_CALORIE_FLOOR_BMR_FACTOR).toInt()
    return when {
        calories < floor -> floor to NutritionCalorieLimit.FLOOR
        calories > NUTRITION_CALORIE_CEILING_KCAL -> NUTRITION_CALORIE_CEILING_KCAL to NutritionCalorieLimit.CEILING
        else -> calories to null
    }
}

fun nutritionEnergyPhase(currentWeightKg: Int, goalWeightKg: Int): NutritionEnergyPhase = when {
    goalWeightKg - currentWeightKg > NUTRITION_TAPER_BAND_KG -> NutritionEnergyPhase.GAIN
    goalWeightKg > currentWeightKg -> NutritionEnergyPhase.TAPER
    else -> NutritionEnergyPhase.MAINTAIN
}

/**
 * The surplus the phase feeds before the weigh-ins have a say.
 *
 * Gain holds the full surplus while more than [NUTRITION_TAPER_BAND_KG] is left to gain; the taper
 * spends it down in proportion to what remains, so the last kilogram is fed almost at maintenance and
 * the day the goal is reached asks for none. At or above the goal the plan maintains: it does not cut,
 * and it does not stop.
 */
fun nutritionPhaseSurplusKcal(phase: NutritionEnergyPhase, currentWeightKg: Int, goalWeightKg: Int): Double =
    when (phase) {
        NutritionEnergyPhase.GAIN -> NUTRITION_GAIN_SURPLUS_KCAL.toDouble()
        NutritionEnergyPhase.TAPER -> NUTRITION_GAIN_SURPLUS_KCAL *
            ((goalWeightKg - currentWeightKg) / NUTRITION_TAPER_BAND_KG).coerceIn(0.0, 1.0)
        NutritionEnergyPhase.MAINTAIN -> 0.0
    }

/** Weigh-ins older than this many days are not a current body, so they are not averaged. */
const val NUTRITION_WEIGH_IN_WINDOW_DAYS = 14L
private const val NUTRITION_AVERAGE_WINDOW_DAYS = 6L

/**
 * The weight the targets are computed for: the mean of the last seven days of weigh-ins, else the
 * newest weigh-in, else the weight typed into the profile. Null when the user has given none of them.
 */
fun nutritionResolvedWeight(
    profile: NutritionProfile,
    weighIns: List<BodyWeightEntry>,
    today: LocalDate
): NutritionResolvedWeight? {
    val dated = weighIns.mapNotNull { entry ->
        runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let { it to entry.weightKg }
    }.filter { (date, weight) -> weight > 0f && !date.isAfter(today) }

    val recent = dated.filter { (date, _) -> !date.isBefore(today.minusDays(NUTRITION_WEIGH_IN_WINDOW_DAYS - 1)) }
    if (recent.isNotEmpty()) {
        val window = recent.filter { (date, _) -> !date.isBefore(today.minusDays(NUTRITION_AVERAGE_WINDOW_DAYS)) }
        val averaged = (window.ifEmpty { recent }).map { it.second }.average().roundToInt()
        if (averaged in NUTRITION_WEIGHT_RANGE) {
            return NutritionResolvedWeight(averaged, NutritionWeightSource.SEVEN_DAY_AVERAGE)
        }
    }

    val latest = dated.maxByOrNull { it.first }?.second?.roundToInt()
    if (latest != null && latest in NUTRITION_WEIGHT_RANGE) {
        return NutritionResolvedWeight(latest, NutritionWeightSource.LATEST_WEIGH_IN)
    }

    val typed = profile.weightKg?.usableIn(NUTRITION_WEIGHT_RANGE) ?: return null
    return NutritionResolvedWeight(typed, NutritionWeightSource.SETTINGS)
}

/**
 * How fast body weight is rising, as a fraction of body weight per week: the mean of the last seven
 * days against the mean of the seven before it.
 *
 * Null unless both windows hold a weigh-in and the two are at least a week apart — one measurement
 * states a weight, never a rate, and a rate read off two days says more about water than about muscle.
 */
fun nutritionWeeklyGainRate(weighIns: List<BodyWeightEntry>, today: LocalDate): Double? {
    val dated = weighIns.mapNotNull { entry ->
        runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let { it to entry.weightKg }
    }.filter { (date, weight) -> weight > 0f && !date.isAfter(today) }

    val recent = dated.filter { (date, _) -> !date.isBefore(today.minusDays(NUTRITION_AVERAGE_WINDOW_DAYS)) }
    val prior = dated.filter { (date, _) ->
        date.isBefore(today.minusDays(NUTRITION_AVERAGE_WINDOW_DAYS)) &&
            !date.isBefore(today.minusDays(NUTRITION_WEIGH_IN_WINDOW_DAYS - 1))
    }
    if (recent.isEmpty() || prior.isEmpty()) return null

    val span = ChronoUnit.DAYS.between(prior.minOf { it.first }, recent.maxOf { it.first })
    if (span < 7) return null

    val priorMean = prior.map { it.second }.average()
    if (priorMean <= 0.0) return null
    return (recent.map { it.second }.average() - priorMean) / priorMean
}

/** The per-kilogram protein target, held inside its band by clamping each end rather than the target. */
fun nutritionProteinTarget(weightKg: Int): Pair<Int, Int> {
    val target = (weightKg * NUTRITION_PROTEIN_GRAMS_PER_KG).roundToInt()
    val low = maxOf(target - NUTRITION_PROTEIN_RANGE_GRAMS, ceil(weightKg * NUTRITION_PROTEIN_MIN_PER_KG).toInt())
    val high = minOf(target + NUTRITION_PROTEIN_RANGE_GRAMS, (weightKg * NUTRITION_PROTEIN_MAX_PER_KG).toInt())
    return low to high
}

/**
 * The whole model for one profile, or the reason it cannot answer.
 *
 * [weighIns] are the Progress screen's entries; they decide the current weight and hold the surplus at
 * the weekly gain limit. Nothing here substitutes a default body for a missing one.
 */
fun nutritionEnergyOutcome(
    profile: NutritionProfile,
    weighIns: List<BodyWeightEntry>,
    today: LocalDate
): NutritionEnergyOutcome {
    val age = profile.ageYears
    if (age != null && age < NUTRITION_AGE_RANGE.first) return NutritionEnergyOutcome.Underage

    val usableAge = age?.usableIn(NUTRITION_AGE_RANGE) ?: return NutritionEnergyOutcome.IncompleteProfile
    val height = profile.heightCm?.usableIn(NUTRITION_HEIGHT_RANGE) ?: return NutritionEnergyOutcome.IncompleteProfile
    val sex = profile.sex ?: return NutritionEnergyOutcome.IncompleteProfile
    val goal = profile.goalWeightKg?.usableIn(NUTRITION_GOAL_WEIGHT_RANGE)
        ?: return NutritionEnergyOutcome.IncompleteProfile
    val weight = nutritionResolvedWeight(profile, weighIns, today) ?: return NutritionEnergyOutcome.IncompleteProfile

    val basal = nutritionBasalMetabolicRate(weight.weightKg, height, usableAge, sex)
    val maintenance = nutritionMaintenanceCalories(basal)
    val phase = nutritionEnergyPhase(weight.weightKg, goal)
    val phaseSurplus = nutritionPhaseSurplusKcal(phase, weight.weightKg, goal)

    val measuredRate = if (phaseSurplus > 0.0) nutritionWeeklyGainRate(weighIns, today) else null
    val surplus = if (measuredRate != null && measuredRate > NUTRITION_WEEKLY_GAIN_LIMIT) {
        phaseSurplus * (NUTRITION_WEEKLY_GAIN_LIMIT / measuredRate)
    } else {
        phaseSurplus
    }
    val (proteinMin, proteinMax) = nutritionProteinTarget(weight.weightKg)

    return NutritionEnergyOutcome.Ready(
        NutritionEnergyPlan(
            basalMetabolicRate = basal,
            maintenanceCalories = maintenance,
            phase = phase,
            weeklySurplusKcal = surplus.roundToInt(),
            heldByWeeklyGain = surplus < phaseSurplus,
            currentWeightKg = weight.weightKg,
            goalWeightKg = goal,
            weightSource = weight.source,
            proteinMinGrams = proteinMin,
            proteinMaxGrams = proteinMax
        )
    )
}
