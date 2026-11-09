package com.monkfitness.app

import com.monkfitness.app.data.model.BiologicalSex
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.NutritionCalorieLimit
import com.monkfitness.app.data.model.NutritionDayType
import com.monkfitness.app.data.model.NutritionEnergyOutcome
import com.monkfitness.app.data.model.NutritionEnergyPhase
import com.monkfitness.app.data.model.NutritionProfile
import com.monkfitness.app.data.model.NutritionProfileError
import com.monkfitness.app.data.model.NutritionProfileField
import com.monkfitness.app.data.model.NutritionWeightSource
import com.monkfitness.app.data.model.generateNutritionPlan
import com.monkfitness.app.data.model.limitNutritionCalories
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionBasalMetabolicRate
import com.monkfitness.app.data.model.nutritionEnergyOutcome
import com.monkfitness.app.data.model.nutritionEnergyPhase
import com.monkfitness.app.data.model.nutritionMaintenanceCalories
import com.monkfitness.app.data.model.nutritionPhaseSurplusKcal
import com.monkfitness.app.data.model.nutritionProfileError
import com.monkfitness.app.data.model.nutritionProteinTarget
import com.monkfitness.app.data.model.nutritionResolvedWeight
import com.monkfitness.app.data.model.nutritionWeeklyGainRate
import com.monkfitness.app.data.model.reportionNutritionPlan
import com.monkfitness.app.data.model.toMealEntities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate

/**
 * The energy model as arithmetic and as policy: the resting rate against hand-computed Mifflin-St Jeor
 * values, the surplus the phases feed, and the limits that hold a target between them.
 */
class NutritionEnergyModelTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadCatalogue() = NutritionCatalogueFixture.load()

        private val TODAY = NutritionProfiles.TODAY
    }

    // Mifflin-St Jeor: 10 x kg + 6.25 x cm - 5 x age + 5 (male) / - 161 (female).
    @Test
    fun theRestingRateMatchesMifflinStJeorForBothSexes() {
        assertEquals(1570, nutritionBasalMetabolicRate(59, 180, 30, BiologicalSex.MALE))
        assertEquals(1680, nutritionBasalMetabolicRate(70, 180, 30, BiologicalSex.MALE))
        assertEquals(1339, nutritionBasalMetabolicRate(60, 168, 30, BiologicalSex.FEMALE))
        assertEquals(1295, nutritionBasalMetabolicRate(55, 165, 25, BiologicalSex.FEMALE))
        assertEquals(1699, nutritionBasalMetabolicRate(80, 175, 40, BiologicalSex.MALE))
    }

    @Test
    fun maintenanceIsTheStatedActivityFactorAndNothingElse() {
        assertEquals(2434, nutritionMaintenanceCalories(1570))
        assertEquals(2604, nutritionMaintenanceCalories(1680))
        assertEquals(2075, nutritionMaintenanceCalories(1339))
    }

    /**
     * The day types split one weekly surplus between them: three training, two light and two rest days
     * must add back up to the surplus the phase asked for, and no day may sit under maintenance.
     */
    @Test
    fun aGainWeekAveragesInsideTheSurplusBandAndNeverDipsUnderMaintenance() {
        val energy = NutritionProfiles.energy(NutritionProfiles.man(59))
        val plan = generateNutritionPlan(seed = 0, startDay = 1, daysCount = 7, energy = energy)

        val weeklySurplus = plan.days.map { it.targetCalories }.average() - energy.maintenanceCalories
        assertTrue("the week averages a $weeklySurplus kcal surplus", weeklySurplus in 250.0..500.0)
        plan.days.forEach { day ->
            assertTrue(
                "day ${day.dayNumber} (${day.dayType}) is under maintenance",
                day.targetCalories >= energy.maintenanceCalories
            )
        }

        val training = plan.days.first { it.dayType == NutritionDayType.TRAINING }
        assertTrue("a training day is ${training.targetCalories} kcal", training.targetCalories in 2800..3000)
        assertTrue(
            "a training day is not the biggest day of the week",
            training.targetCalories > plan.days.first { it.dayType == NutritionDayType.REST }.targetCalories
        )
    }

    @Test
    fun theTaperSpendsTheSurplusDownAndTheGoalMaintains() {
        assertEquals(NutritionEnergyPhase.GAIN, nutritionEnergyPhase(currentWeightKg = 59, goalWeightKg = 63))
        assertEquals(NutritionEnergyPhase.TAPER, nutritionEnergyPhase(currentWeightKg = 59, goalWeightKg = 62))
        assertEquals(NutritionEnergyPhase.TAPER, nutritionEnergyPhase(currentWeightKg = 68, goalWeightKg = 70))
        assertEquals(NutritionEnergyPhase.MAINTAIN, nutritionEnergyPhase(currentWeightKg = 70, goalWeightKg = 70))
        assertEquals(NutritionEnergyPhase.MAINTAIN, nutritionEnergyPhase(currentWeightKg = 71, goalWeightKg = 70))

        assertEquals(400.0, nutritionPhaseSurplusKcal(NutritionEnergyPhase.GAIN, 59, 70), 0.001)
        assertEquals(400.0, nutritionPhaseSurplusKcal(NutritionEnergyPhase.TAPER, 67, 70), 0.001)
        assertEquals(266.67, nutritionPhaseSurplusKcal(NutritionEnergyPhase.TAPER, 68, 70), 0.01)
        assertEquals(133.33, nutritionPhaseSurplusKcal(NutritionEnergyPhase.TAPER, 69, 70), 0.01)
        assertEquals(0.0, nutritionPhaseSurplusKcal(NutritionEnergyPhase.TAPER, 70, 70), 0.001)
        assertEquals(0.0, nutritionPhaseSurplusKcal(NutritionEnergyPhase.MAINTAIN, 72, 70), 0.001)
    }

    /** The taper is continuous: the first kilogram of it asks for exactly the gain surplus. */
    @Test
    fun theSurplusFallsWithoutAStepAtEitherEndOfTheTaper() {
        val atBand = NutritionProfiles.energy(NutritionProfiles.man(67, goalWeightKg = 70))
        val insideBand = NutritionProfiles.energy(NutritionProfiles.man(68, goalWeightKg = 70))
        val atGoal = NutritionProfiles.energy(NutritionProfiles.man(70, goalWeightKg = 70))

        assertEquals(400, atBand.weeklySurplusKcal)
        assertEquals(267, insideBand.weeklySurplusKcal)
        assertEquals(0, atGoal.weeklySurplusKcal)
        assertEquals(NutritionEnergyPhase.MAINTAIN, atGoal.phase)
        assertEquals(atGoal.maintenanceCalories, atGoal.targetsFor(NutritionDayType.TRAINING).dailyCalories)
    }

    @Test
    fun aGoalAtOrBelowTheCurrentWeightMaintainsInsteadOfCutting() {
        listOf(70, 65).forEach { goal ->
            val energy = NutritionProfiles.energy(NutritionProfiles.man(70, goalWeightKg = goal))
            assertEquals(NutritionEnergyPhase.MAINTAIN, energy.phase)
            assertEquals(0, energy.weeklySurplusKcal)
            NutritionDayType.entries.forEach { dayType ->
                assertEquals(
                    "a $dayType day still asks for maintenance",
                    energy.maintenanceCalories,
                    energy.targetsFor(dayType).dailyCalories
                )
            }
        }
    }

    @Test
    fun theWeeklyGainLimitNeedsTwoWeighInsAWeekApart() {
        assertNull("no weigh-ins cannot state a rate", nutritionWeeklyGainRate(emptyList(), TODAY))
        assertNull(
            "one weigh-in cannot state a rate",
            nutritionWeeklyGainRate(listOf(BodyWeightEntry(weightKg = 59f, date = TODAY.toString())), TODAY)
        )

        val fast = listOf(
            BodyWeightEntry(weightKg = 59f, date = TODAY.minusDays(9).toString()),
            BodyWeightEntry(weightKg = 60.5f, date = TODAY.minusDays(1).toString())
        )
        val measured = nutritionWeeklyGainRate(fast, TODAY)
        assertTrue("a 1.5 kg rise in eight days reads as $measured", measured != null && measured > 0.005)

        val slow = listOf(
            BodyWeightEntry(weightKg = 59f, date = TODAY.minusDays(9).toString()),
            BodyWeightEntry(weightKg = 59.1f, date = TODAY.minusDays(1).toString())
        )
        val gentle = nutritionWeeklyGainRate(slow, TODAY)
        assertTrue("a 0.1 kg rise in eight days reads as $gentle", gentle != null && gentle < 0.005)
    }

    /** A rate above the limit pulls the surplus down to it; a flat or falling weight changes nothing. */
    @Test
    fun theWeeklyGainLimitHoldsTheSurplusAndAFastGainIsTheOnlyThingThatMovesIt() {
        val gaining = listOf(
            BodyWeightEntry(weightKg = 59f, date = TODAY.minusDays(9).toString()),
            BodyWeightEntry(weightKg = 60.5f, date = TODAY.minusDays(2).toString()),
            BodyWeightEntry(weightKg = 60.5f, date = TODAY.minusDays(1).toString())
        )
        val held = NutritionProfiles.energy(NutritionProfiles.man(59), gaining)
        assertTrue("the surplus was not held: ${held.weeklySurplusKcal}", held.heldByWeeklyGain)
        assertTrue("the surplus is ${held.weeklySurplusKcal}", held.weeklySurplusKcal in 1..399)

        val flat = listOf(
            BodyWeightEntry(weightKg = 59f, date = TODAY.minusDays(9).toString()),
            BodyWeightEntry(weightKg = 59f, date = TODAY.minusDays(1).toString())
        )
        val unchanged = NutritionProfiles.energy(NutritionProfiles.man(59), flat)
        assertEquals(400, unchanged.weeklySurplusKcal)
        assertTrue(!unchanged.heldByWeeklyGain)
    }

    @Test
    fun theWeightComesFromTheWeighInsThenTheNewestOneThenTheTypedField() {
        val profile = NutritionProfiles.man(59)
        val typed = nutritionResolvedWeight(profile, emptyList(), TODAY)
        assertEquals(59, typed?.weightKg)
        assertEquals(NutritionWeightSource.SETTINGS, typed?.source)

        val recent = listOf(
            BodyWeightEntry(weightKg = 60f, date = TODAY.minusDays(2).toString()),
            BodyWeightEntry(weightKg = 61f, date = TODAY.minusDays(1).toString())
        )
        val averaged = nutritionResolvedWeight(profile, recent, TODAY)
        assertEquals(61, averaged?.weightKg)
        assertEquals(NutritionWeightSource.SEVEN_DAY_AVERAGE, averaged?.source)

        val stale = listOf(BodyWeightEntry(weightKg = 63f, date = TODAY.minusDays(30).toString()))
        val latest = nutritionResolvedWeight(profile, stale, TODAY)
        assertEquals(63, latest?.weightKg)
        assertEquals(NutritionWeightSource.LATEST_WEIGH_IN, latest?.source)

        assertNull(
            "no weigh-in and no typed weight is not a body",
            nutritionResolvedWeight(NutritionProfile(), emptyList(), TODAY)
        )
    }

    @Test
    fun everyFieldRefusesWhatItCannotUseAndSaysWhy() {
        assertNull(nutritionProfileError(NutritionProfileField.WEIGHT, "59"))
        assertEquals(NutritionProfileError.OUT_OF_RANGE, nutritionProfileError(NutritionProfileField.WEIGHT, "34"))
        assertEquals(NutritionProfileError.OUT_OF_RANGE, nutritionProfileError(NutritionProfileField.WEIGHT, "221"))
        assertNull(nutritionProfileError(NutritionProfileField.HEIGHT, "120"))
        assertEquals(NutritionProfileError.OUT_OF_RANGE, nutritionProfileError(NutritionProfileField.HEIGHT, "119"))
        assertNull(nutritionProfileError(NutritionProfileField.AGE, "18"))
        assertEquals(NutritionProfileError.OUT_OF_RANGE, nutritionProfileError(NutritionProfileField.AGE, "91"))
        assertEquals(NutritionProfileError.ADULTS_ONLY, nutritionProfileError(NutritionProfileField.AGE, "17"))
        assertNull(nutritionProfileError(NutritionProfileField.GOAL_WEIGHT, "220"))
        assertEquals(
            NutritionProfileError.OUT_OF_RANGE,
            nutritionProfileError(NutritionProfileField.GOAL_WEIGHT, "221")
        )
        // A field being typed is not a field that is wrong.
        assertNull(nutritionProfileError(NutritionProfileField.AGE, ""))
    }

    @Test
    fun aBodyUnderEighteenIsToldTheTargetsAreForAdultsAndIsNotPlanned() {
        val underage = NutritionProfile(weightKg = 59, heightCm = 175, ageYears = 17, sex = BiologicalSex.MALE, goalWeightKg = 70)
        assertEquals(NutritionEnergyOutcome.Underage, nutritionEnergyOutcome(underage, emptyList(), TODAY))
    }

    @Test
    fun anIncompleteProfileIsNotStandInForByADefaultBody() {
        val incomplete = listOf(
            NutritionProfile(),
            NutritionProfile(weightKg = 59),
            NutritionProfile(weightKg = 59, heightCm = 180),
            NutritionProfile(weightKg = 59, heightCm = 180, ageYears = 30),
            NutritionProfile(weightKg = 59, heightCm = 180, ageYears = 30, sex = BiologicalSex.MALE),
            NutritionProfile(weightKg = 300, heightCm = 180, ageYears = 30, sex = BiologicalSex.MALE, goalWeightKg = 70)
        )
        incomplete.forEach { profile ->
            assertEquals(
                "$profile was planned for",
                NutritionEnergyOutcome.IncompleteProfile,
                nutritionEnergyOutcome(profile, emptyList(), TODAY)
            )
        }
    }

    @Test
    fun proteinStaysInsideItsPerKilogramBandAtEverySupportedWeight() {
        (45..130).forEach { weight ->
            val (low, high) = nutritionProteinTarget(weight)
            assertTrue("$weight kg: $low g is under 1.6 g/kg", low >= weight * 1.6)
            assertTrue("$weight kg: $high g is over 2.2 g/kg", high <= weight * 2.2)
            assertTrue("$weight kg: the range is only ${high - low} g wide", high - low >= 19)
            assertTrue("$weight kg: the target is outside its own range", weight * 2 in low..high)
        }
        // The old absolute clamp is gone: 45 kg is no longer raised to a 110 g floor.
        assertEquals(80 to 99, nutritionProteinTarget(45))
        assertEquals(108 to 128, nutritionProteinTarget(59))
        assertEquals(250 to 270, nutritionProteinTarget(130))
    }

    @Test
    fun aDayIsHeldBetweenTheRestingFloorAndTheCeiling() {
        val (floor, floorLimit) = limitNutritionCalories(1000, 1570)
        assertEquals(1884, floor)
        assertEquals(NutritionCalorieLimit.FLOOR, floorLimit)

        val (ceiling, ceilingLimit) = limitNutritionCalories(6000, 1570)
        assertEquals(4500, ceiling)
        assertEquals(NutritionCalorieLimit.CEILING, ceilingLimit)

        val (inside, noLimit) = limitNutritionCalories(2954, 1570)
        assertEquals(2954, inside)
        assertNull(noLimit)
    }

    /** The ceiling is reachable by a body the range accepts, so it is a limit and not a decoration. */
    @Test
    fun theCeilingBindsForTheHeaviestBodiesTheRangesAllow() {
        val heaviest = NutritionProfiles.energy(NutritionProfiles.man(220, heightCm = 230, ageYears = 18, goalWeightKg = 220))
        val training = heaviest.forDay(NutritionDayType.TRAINING)

        assertEquals(4500, training.targets.dailyCalories)
        assertEquals(NutritionCalorieLimit.CEILING, training.limit)
        assertTrue("the ceiling is above the ordinary day it caps", training.surplusCalories < 0)
    }

    @Test
    fun aGeneratedDayIsPricedFromTheSameModelTheCardReads() {
        val energy = NutritionProfiles.energy(NutritionProfiles.man(59))
        val plan = generateNutritionPlan(seed = 4, startDay = 1, daysCount = 7, energy = energy)

        plan.days.forEach { day ->
            assertEquals(energy.targetsFor(day.dayType).dailyCalories, day.targetCalories)
            assertEquals(energy.proteinMaxGrams, energy.targetsFor(day.dayType).proteinMaxGrams)
        }
    }

    /**
     * Re-portioning moves the days still to come onto the new target and leaves every past day, and every
     * dish it already served, exactly as stored.
     */
    @Test
    fun reportioningTouchesOnlyTheDaysAfterTodayAndKeepsTheirDishes() {
        val before = NutritionProfiles.energy(NutritionProfiles.man(59))
        val stored = mealEntitiesToNutritionPlan(
            cycleId = 3L,
            meals = generateNutritionPlan(seed = 6, startDay = 1, daysCount = 7, energy = before, cycleId = 3L)
                .toMealEntities(3L)
        )
        val after = NutritionProfiles.energy(NutritionProfiles.man(63))

        val reportioned = reportionNutritionPlan(stored, todayDayNumber = 3, energy = after)

        assertEquals(7, reportioned.days.size)
        reportioned.days.take(3).zip(stored.days.take(3)).forEach { (rebuilt, original) ->
            assertEquals(original.totalCalories, rebuilt.totalCalories)
            assertEquals(
                original.meals.map { it.templateId },
                rebuilt.meals.map { it.templateId }
            )
            assertEquals(
                original.meals.flatMap { meal -> meal.ingredients.map { it.ingredient.key to it.amount } },
                rebuilt.meals.flatMap { meal -> meal.ingredients.map { it.ingredient.key to it.amount } }
            )
        }

        val future = reportioned.days.drop(3).zip(stored.days.drop(3))
        assertTrue("no future day changed", future.any { (rebuilt, original) -> rebuilt.totalCalories != original.totalCalories })
        future.forEach { (rebuilt, original) ->
            assertEquals("a future day changed its dishes", original.meals.map { it.templateId }, rebuilt.meals.map { it.templateId })
            assertTrue(
                "day ${original.dayNumber} is ${rebuilt.totalCalories} against a ${after.targetsFor(original.dayType).dailyCalories} target",
                kotlin.math.abs(rebuilt.totalCalories - after.targetsFor(original.dayType).dailyCalories) * 100 <=
                    after.targetsFor(original.dayType).dailyCalories * 7
            )
        }
        assertEquals(stored.days.size, reportioned.days.size)
    }

    @Test
    fun reportioningTodayOrLaterIsANoOpWhenNothingIsLeftToCome() {
        val energy = NutritionProfiles.energy(NutritionProfiles.man(59))
        val stored = generateNutritionPlan(seed = 2, startDay = 1, daysCount = 3, energy = energy, cycleId = 9L)

        assertEquals(stored, reportionNutritionPlan(stored, todayDayNumber = null, energy = energy))
        assertEquals(stored, reportionNutritionPlan(stored, todayDayNumber = 3, energy = energy))
    }

    @Test
    fun aStoredDayIsWorthReportioningOnlyPastFivePercent() {
        assertTrue(com.monkfitness.app.data.model.nutritionReportionNeeded(2700, 2954))
        assertTrue(!com.monkfitness.app.data.model.nutritionReportionNeeded(2900, 2954))
        assertTrue(!com.monkfitness.app.data.model.nutritionReportionNeeded(0, 2954))
    }

    @Test
    fun theWorkedExampleTheProductAsksFor() {
        val energy = NutritionProfiles.energy(NutritionProfiles.man(59, goalWeightKg = 70))

        assertEquals(1570, energy.basalMetabolicRate)
        assertEquals(2434, energy.maintenanceCalories)
        assertEquals(NutritionEnergyPhase.GAIN, energy.phase)
        assertEquals(400, energy.weeklySurplusKcal)
        assertEquals(2954, energy.targetsFor(NutritionDayType.TRAINING).dailyCalories)
        assertEquals(2834, energy.targetsFor(NutritionDayType.LIGHT).dailyCalories)
        assertEquals(2654, energy.targetsFor(NutritionDayType.REST).dailyCalories)
        assertEquals(108, energy.targetsFor(NutritionDayType.TRAINING).proteinMinGrams)
        assertEquals(128, energy.targetsFor(NutritionDayType.TRAINING).proteinMaxGrams)
    }

    @Test
    fun aWomanIsPlannedForOnHerOwnRestingRate() {
        val energy = NutritionProfiles.energy(NutritionProfiles.woman(60, goalWeightKg = 70))

        assertEquals(1339, energy.basalMetabolicRate)
        assertEquals(2075, energy.maintenanceCalories)
        assertEquals(2595, energy.targetsFor(NutritionDayType.TRAINING).dailyCalories)
        assertEquals(2475, energy.targetsFor(NutritionDayType.LIGHT).dailyCalories)
        assertEquals(2295, energy.targetsFor(NutritionDayType.REST).dailyCalories)
    }

    @Test
    fun aWeighInInTheFutureIsIgnored() {
        val profile = NutritionProfiles.man(59)
        val future = listOf(BodyWeightEntry(weightKg = 80f, date = TODAY.plusDays(30).toString()))
        val resolved = nutritionResolvedWeight(profile, future, TODAY)

        assertEquals(59, resolved?.weightKg)
        assertEquals(NutritionWeightSource.SETTINGS, resolved?.source)
    }
}
