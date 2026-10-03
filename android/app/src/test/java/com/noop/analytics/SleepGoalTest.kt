package com.noop.analytics

import com.noop.ui.ProfileStore
import com.noop.ui.shouldAskSleepGoal
import com.noop.ui.stepSleepGoal
import com.noop.ui.formatSleepGoal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's own sleep goal (fork addition): it must really reach every need the scorer reads. */
class SleepGoalTest {

    @After fun reset() { RestScorer.userSleepGoalHours = null }

    private val week = List(10) { 7.6 }

    @Test fun withoutAGoalTheAutomaticNeedIsUnchanged() {
        assertEquals(8.0, RestScorer.personalizedNeedHours(week, 45), 1e-9)
        assertEquals(8.0, RestScorer.fallbackNeedHours, 1e-9)
        assertEquals(450.0, RestScorer.descriptiveNeedMin(400.0), 1e-9)
    }

    @Test fun aGoalReplacesTheNeedEverywhere() {
        RestScorer.userSleepGoalHours = 6.5
        assertEquals(6.5, RestScorer.personalizedNeedHours(week, 45), 1e-9)
        assertEquals(6.5, RestScorer.fallbackNeedHours, 1e-9)
        assertEquals(390.0, RestScorer.descriptiveNeedMin(400.0), 1e-9)
    }

    @Test fun theGoalChangesTheScoreThroughTheDurationTermOnly() {
        // 6 h asleep: against 8 h the duration term is 75, against 6.5 h it is ~92.3.
        val auto = RestScorer.rest(6 * 3600.0, 0.9, 3600.0, 5400.0, consistency = 0.5)!!
        RestScorer.userSleepGoalHours = 6.5
        val goal = RestScorer.rest(6 * 3600.0, 0.9, 3600.0, 5400.0, consistency = 0.5)!!
        assertEquals(0.5 * (6.0 / 6.5 * 100.0 - 75.0), goal - auto, 0.01)
        // An explicit need passed by a caller still wins over the goal.
        assertEquals(auto, RestScorer.rest(6 * 3600.0, 0.9, 3600.0, 5400.0, sleepNeedHours = 8.0, consistency = 0.5)!!, 1e-9)
    }

    @Test fun theDebtFollowsTheGoal() {
        RestScorer.userSleepGoalHours = 7.0
        val need = RestScorer.personalizedNeedHours(week, null)
        val series = SleepDebt.debtSeries(series = listOf("2026-10-01" to 420.0, "2026-10-02" to 420.0), needHours = need)
        assertTrue(series.all { it.second == 0.0 })
    }

    @Test fun theGoalIsBoundedAndZeroMeansAutomatic() {
        RestScorer.userSleepGoalHours = 3.0
        assertEquals(RestScorer.SLEEP_GOAL_MIN_HOURS, RestScorer.userSleepGoalHours!!, 1e-9)
        RestScorer.userSleepGoalHours = 14.0
        assertEquals(RestScorer.SLEEP_GOAL_MAX_HOURS, RestScorer.userSleepGoalHours!!, 1e-9)
        RestScorer.userSleepGoalHours = 0.0
        assertNull(RestScorer.userSleepGoalHours)
    }

    @Test fun storedMinutesSnapToTheGridAndStepperSeedsSevenHours() {
        assertEquals(0, ProfileStore.normalizeSleepGoal(0))
        assertEquals(390, ProfileStore.normalizeSleepGoal(392))
        assertEquals(360, ProfileStore.normalizeSleepGoal(100))
        assertEquals(600, ProfileStore.normalizeSleepGoal(900))
        assertEquals(435, stepSleepGoal(0, up = true))
        assertEquals(405, stepSleepGoal(0, up = false))
        assertEquals(360, stepSleepGoal(360, up = false))
        assertEquals("6:30 h", formatSleepGoal(390))
    }

    @Test fun theQuestionWaitsForAWeekAndIsAskedOnce() {
        assertFalse(shouldAskSleepGoal(asked = false, recordedNights = 6))
        assertTrue(shouldAskSleepGoal(asked = false, recordedNights = 7))
        assertFalse(shouldAskSleepGoal(asked = true, recordedNights = 30))
    }

    @Test fun theProfileCacheKeyChangesWithTheGoalButNotWithoutOne() {
        val base = UserProfile()
        assertEquals(base.cacheKey, UserProfile(sleepGoalHours = 0.0).cacheKey)
        assertNotEquals(base.cacheKey, UserProfile(sleepGoalHours = 6.5).cacheKey)
    }
}
