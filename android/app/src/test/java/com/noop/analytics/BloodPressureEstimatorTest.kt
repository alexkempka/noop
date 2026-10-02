package com.noop.analytics

import com.noop.analytics.BloodPressureEstimator.CuffReading
import com.noop.analytics.BloodPressureEstimator.NightFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BloodPressureEstimatorTest {

    private val day = 86_400L
    private val t0 = 1_780_000_000L

    /** Three readings two minutes apart: one complete session. */
    private fun session(at: Long, sys: Double, dia: Double, spread: Double = 0.0) = listOf(
        CuffReading(at, sys - spread, dia - spread),
        CuffReading(at + 120, sys, dia),
        CuffReading(at + 240, sys + spread, dia + spread),
    )

    private fun night(i: Int, rhr: Double? = null) =
        NightFeatures(day = "d$i", wakeTs = t0 + i * day - 3600, restingHr = rhr)

    @Test
    fun readingsTenMinutesApartFormOneSession() {
        val s = BloodPressureEstimator.sessions(session(t0, 120.0, 80.0) + CuffReading(t0 + 3600, 130.0, 85.0))
        assertEquals(2, s.size)
        assertTrue(s[0].complete)
        assertFalse(s[1].complete)
        assertEquals(120.0, s[0].systolic, 1e-9)
    }

    @Test
    fun noEstimateBeforeACompleteCalibration() {
        val a = BloodPressureEstimator.assess(session(t0, 120.0, 80.0).take(2), listOf(night(0)), t0 + 300)
        assertTrue(a.estimates.isEmpty())
        assertEquals(0, a.completeSessions)
        assertNotNull(a.openSession)
    }

    @Test
    fun oneSessionSetsTheLevelAndTheSpreadStaysUnknown() {
        val a = BloodPressureEstimator.assess(session(t0, 124.0, 81.0), listOf(night(0, 55.0), night(1, 60.0)), t0 + day)
        assertEquals(2, a.estimates.size)
        a.estimates.forEach {
            assertEquals(124.0, it.systolic, 1e-6)
            assertEquals(81.0, it.diastolic, 1e-6)
            assertNull(it.spreadSys)
        }
    }

    @Test
    fun everyNewCalibrationMovesTheEstimate() {
        val nights = (0..10).map { night(it, 58.0) }
        val one = BloodPressureEstimator.assess(session(t0, 120.0, 80.0), nights, t0 + 20 * day)
        val two = BloodPressureEstimator.assess(
            session(t0, 120.0, 80.0) + session(t0 + 5 * day, 130.0, 86.0), nights, t0 + 20 * day,
        )
        assertTrue(two.estimates.first().systolic > one.estimates.first().systolic)
        assertTrue(two.estimates.first().diastolic > one.estimates.first().diastolic)
    }

    @Test
    fun theModelLearnsARelationshipTheCuffConfirmsAndGetsMorePrecise() {
        // Truth for this wearer: systolic = 70 + resting HR, diastolic = 40 + 0.7 * resting HR.
        val rhrs = listOf(52.0, 64.0, 57.0, 70.0, 55.0, 66.0, 60.0, 72.0, 54.0, 68.0, 58.0, 63.0, 51.0, 69.0)
        val nights = rhrs.mapIndexed { i, r -> night(i, r) }
        val readings = rhrs.indices.flatMap { i ->
            session(t0 + i * day + 1800, 70.0 + rhrs[i], 40.0 + 0.7 * rhrs[i])
        }
        val a = BloodPressureEstimator.assess(readings, nights, t0 + 15 * day)
        assertEquals(rhrs.size - 1, a.comparisons.size)
        val early = a.comparisons.take(3).map { abs(it.errSys) }.average()
        val late = a.comparisons.takeLast(3).map { abs(it.errSys) }.average()
        assertTrue("late $late should beat early $early", late < early)
        // A high-HR night now reads higher than a low-HR night.
        val hi = a.estimates.first { it.day == "d7" }
        val lo = a.estimates.first { it.day == "d12" }
        assertTrue(hi.systolic > lo.systolic + 5)
        assertNotNull(hi.spreadSys)
    }

    @Test
    fun aSignalTheCuffDoesNotConfirmBarelyMovesTheEstimate() {
        val rhrs = listOf(52.0, 64.0, 57.0, 70.0, 55.0, 66.0)
        val nights = rhrs.mapIndexed { i, r -> night(i, r) }
        val readings = rhrs.indices.flatMap { i -> session(t0 + i * day + 1800, 120.0, 80.0) }
        val a = BloodPressureEstimator.assess(readings, nights, t0 + 7 * day)
        val spread = a.estimates.maxOf { it.systolic } - a.estimates.minOf { it.systolic }
        assertTrue("spread $spread", spread < 0.5)
    }

    @Test
    fun implausibleCuffValuesNeverReachTheModel() {
        assertFalse(BloodPressureEstimator.plausible(80.0, 120.0))
        assertFalse(BloodPressureEstimator.plausible(1200.0, 80.0))
        val a = BloodPressureEstimator.assess(
            session(t0, 120.0, 80.0).dropLast(1) + CuffReading(t0 + 240, 12.0, 8.0), listOf(night(0)), t0 + 300,
        )
        assertEquals(0, a.completeSessions)
    }

    @Test
    fun recalibrationIsSuggestedAfterFourWeeks() {
        val a = BloodPressureEstimator.assess(session(t0, 120.0, 80.0), listOf(night(0)), t0 + 29 * day)
        assertTrue(a.calibrationDue)
        assertEquals(1, a.estimates.size)
    }

    @Test
    fun aSessionPairsWithTheNightBeforeIt() {
        val s = BloodPressureEstimator.sessions(session(t0 + 2 * day + 600, 120.0, 80.0)).first()
        val nights = listOf(night(1), night(2), night(3))
        assertEquals("d2", BloodPressureEstimator.nightFor(s, nights)?.day)
    }

    // MARK: pulse shape

    /** A synthetic pulse: fast rise over [rise] samples, slow fall over the rest of a [period]. */
    private fun pulseWave(seconds: Int, period: Int, rise: Int, invert: Boolean = false): List<Pair<Long, List<Long>>> {
        val n = seconds * BloodPressureEstimator.PPG_SAMPLE_RATE
        val x = LongArray(n) { i ->
            val p = i % period
            val v = if (p < rise) sin(PI / 2 * p / rise) else sin(PI / 2 * (1 + (p - rise).toDouble() / (period - rise)))
            val s = (1000 * v).toLong() + 50_000
            if (invert) 100_000 - s else s
        }
        return (0 until seconds).map { s ->
            (t0 + s) to x.slice(s * 25 until (s + 1) * 25).toList()
        }
    }

    @Test
    fun pulseShapeFindsTheRiseAndCopesWithAnInvertedSignal() {
        for (invert in listOf(false, true)) {
            val shape = BloodPressureEstimator.pulseShape(pulseWave(seconds = 600, period = 20, rise = 5, invert = invert))
            assertNotNull(shape)
            assertEquals(200.0, shape!!.riseMs, 45.0)
            assertTrue(shape.riseFraction < 0.4)
            assertTrue(shape.beats >= BloodPressureEstimator.MIN_BEATS)
        }
    }

    @Test
    fun tooLittleWaveformGivesNoPulseShape() {
        assertNull(BloodPressureEstimator.pulseShape(pulseWave(seconds = 60, period = 20, rise = 5)))
        assertNull(BloodPressureEstimator.pulseShape(emptyList()))
    }
}
