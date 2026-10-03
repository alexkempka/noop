package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StepGoalLogicTest {

    private val today = LocalDate.of(2026, 10, 3)
    private fun day(daysAgo: Long, steps: Int?) = DailyMetric(deviceId = "my-whoop", day = today.minusDays(daysAgo).toString(), steps = steps)

    @Test fun goalsSnapToTheGridAndZeroMeansNone() {
        assertEquals(0, StepGoalLogic.normalize(0))
        assertEquals(5_400, StepGoalLogic.normalize(5_365))
        assertEquals(StepGoalLogic.MIN, StepGoalLogic.normalize(200))
        assertEquals(StepGoalLogic.MAX, StepGoalLogic.normalize(90_000))
    }

    @Test fun theWindowKeepsOnlyCountedDaysInsideThirtyDays() {
        val w = StepGoalLogic.window(listOf(day(0, 5_000), day(29, 6_000), day(30, 9_999), day(3, null), day(4, 0)), today)
        assertEquals(listOf(6_000, 5_000), w.map { it.second })
    }

    @Test fun anAverageNeedsThreeDays() {
        assertNull(StepGoalLogic.average(StepGoalLogic.window(listOf(day(0, 5_000), day(1, 6_000)), today)))
        assertEquals(5_000, StepGoalLogic.average(StepGoalLogic.window(listOf(day(0, 4_000), day(1, 5_000), day(2, 6_000)), today)))
    }

    @Test fun suggestionsAreTheAverageATenthAboveAndRoundNumbers() {
        assertEquals(listOf(4_900, 5_400, 7_500, 10_000), StepGoalLogic.suggestions(4_860))
        assertEquals(listOf(7_500, 10_000), StepGoalLogic.suggestions(null))
    }

    @Test fun theFirstStepperTapStartsAtTheAverage() {
        assertEquals(4_900, StepGoalLogic.step(0, up = true, average = 4_860))
        assertEquals(7_500, StepGoalLogic.step(0, up = false, average = null))
        assertEquals(5_900, StepGoalLogic.step(5_400, up = true, average = 4_860))
        assertEquals(StepGoalLogic.MIN, StepGoalLogic.step(1_000, up = false, average = null))
    }

    @Test fun daysReachedCountsTheDaysAtOrAboveTheGoal() {
        val w = StepGoalLogic.window(listOf(day(0, 5_400), day(1, 5_399), day(2, 8_000)), today)
        assertEquals(2, StepGoalLogic.daysReached(w, 5_400))
        assertEquals(0, StepGoalLogic.daysReached(w, 0))
    }
}
