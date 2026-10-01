package com.noop.ui

import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skin-temperature column is bimodal and the Charge driver did not know it.
 *
 * `skinTempDevC` holds a baseline DEVIATION when the on-device pipeline wrote it and an ABSOLUTE
 * wrist temperature when a WHOOP CSV import did (#622/#1705). `WhoopRepository` and
 * `HealthVitalsLogic` have guarded for that for a while; [recoveryChargeDrivers] handed the raw
 * column straight to a parameter whose own documentation says "deviation from the personal
 * baseline". On an import-only install the driver therefore read a worn wrist at ~33 °C as
 * "33 degrees above your own baseline" and took the full penalty off Charge, which the breakdown
 * sheet then printed back as "+33.3 C vs baseline".
 *
 * These tests pin the conversion, not the layout: a deviation stays untouched, an absolute becomes a
 * deviation against the absolute nights' own baseline, and too little history abstains rather than
 * guessing.
 */
class SkinTempScaleTest {

    private fun day(key: String, skin: Double?) =
        DailyMetric(deviceId = "my-whoop", day = key, skinTempDevC = skin)

    /** Fourteen nights around 33.4 °C — an ordinary imported history, every value an ABSOLUTE. */
    private fun absoluteHistory(): List<DailyMetric> =
        (1..14).map { i -> day("2026-09-%02d".format(i), 33.4) }

    // ── Which scale a number is on ───────────────────────────────────────────────────────────────

    @Test
    fun `a worn wrist temperature is recognised as absolute, a drift as a deviation`() {
        // The whole bug in one line: 33.2 is a wrist, not a 33-degree fever.
        assertTrue(VitalBands.isAbsoluteSkinTemp(33.2))
        assertTrue(VitalBands.isAbsoluteSkinTemp(28.0))
        assertTrue(!VitalBands.isAbsoluteSkinTemp(0.6))
        assertTrue(!VitalBands.isAbsoluteSkinTemp(-1.2))
    }

    // ── What the driver is handed ────────────────────────────────────────────────────────────────

    @Test
    fun `a deviation is passed through untouched`() {
        // A strap-only install must see no change at all from this fix.
        val history = (1..14).map { i -> day("2026-09-%02d".format(i), 0.1) }
        val today = day("2026-09-15", 0.4)
        assertEquals(0.4, skinTempDeviation(today, history + today)!!, 1e-9)
    }

    @Test
    fun `an absolute becomes the deviation from the absolute nights' own baseline`() {
        val history = absoluteHistory()
        // Tonight runs half a degree warm against a 33.4 °C baseline.
        val today = day("2026-09-15", 33.9)
        val dev = skinTempDeviation(today, history + today)!!
        // The exact figure depends on the robust EWMA fold; what must hold is that it reads as a
        // DRIFT of roughly half a degree and never again as a 33-degree one.
        assertTrue("expected a small drift, got $dev", dev > 0.0 && dev < 1.5)
        assertTrue(!VitalBands.isAbsoluteSkinTemp(dev))
    }

    @Test
    fun `an absolute with no history abstains rather than scoring against nothing`() {
        val today = day("2026-09-15", 33.4)
        // One night cannot fold a usable baseline. A driver that abstains is honest; one that scores
        // against an unusable baseline is the bug this file exists for.
        assertNull(skinTempDeviation(today, listOf(today)))
    }

    @Test
    fun `a night with no skin temperature at all yields nothing`() {
        val today = day("2026-09-15", null)
        assertNull(skinTempDeviation(today, absoluteHistory() + today))
    }

    @Test
    fun `absolute nights never leak into the result as their own penalty`() {
        // REGRESSION. Before the fix this returned 33.4 — the input, unconverted — and the Charge
        // driver scored it as a 33-degree drift.
        val history = absoluteHistory()
        val today = day("2026-09-15", 33.4)
        val dev = skinTempDeviation(today, history + today)
        assertTrue("a wrist temperature must never reach the driver", dev == null || dev < 1.0)
    }
}
