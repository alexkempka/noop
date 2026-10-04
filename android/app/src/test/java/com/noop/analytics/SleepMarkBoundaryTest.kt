package com.noop.analytics

import com.noop.ui.SleepMarkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepMarkBoundaryTest {
    private val h = 3600L
    private fun bed(t: Long) = SleepMarkBoundary.Mark(SleepMarkType.BEDTIME, t)
    private fun wake(t: Long) = SleepMarkBoundary.Mark(SleepMarkType.WAKE, t)

    @Test fun noTapsChangeNothing() = assertNull(SleepMarkBoundary.adjusted(0, 8 * h, emptyList()))

    @Test fun aBedtimeTapAfterTheDetectedOnsetMovesItLater() {
        // The couch case: detected 01:49, tapped "going to sleep" at 03:00.
        assertEquals(71 * 60L to 10 * h, SleepMarkBoundary.adjusted(0, 10 * h, listOf(bed(71 * 60L))))
    }

    @Test fun aBedtimeTapBeforeTheDetectedOnsetKeepsDetection() =
        assertNull(SleepMarkBoundary.adjusted(h, 9 * h, listOf(bed(h - 600))))

    @Test fun aWakeTapBeforeTheDetectedEndMovesItEarlier() =
        assertEquals(0L to 7 * h, SleepMarkBoundary.adjusted(0, 8 * h, listOf(wake(7 * h))))

    @Test fun aWakeTapAfterTheDetectedEndKeepsDetection() =
        // 04.10.: detected 11:13, tapped awake 11:47 — the end stays.
        assertNull(SleepMarkBoundary.adjusted(0, 8 * h, listOf(wake(8 * h + 34 * 60))))

    @Test fun tapsOutOfReachBelongToAnotherNight() {
        assertNull(SleepMarkBoundary.adjusted(0, 8 * h, listOf(bed(4 * h))))      // 4 h after onset
        assertNull(SleepMarkBoundary.adjusted(0, 8 * h, listOf(wake(4 * h))))     // 4 h before end
    }

    @Test fun aNightIsNeverCollapsedByAStrayTap() {
        // A wake tap 30 min after onset, or a bedtime tap 30 min before the end, would leave < 1 h.
        assertNull(SleepMarkBoundary.adjusted(0, 2 * h, listOf(wake(30 * 60L))))
        assertNull(SleepMarkBoundary.adjusted(0, 2 * h, listOf(bed(90 * 60L))))
        // Both together: the bedtime tap is refused, the wake tap still leaves 100 min.
        assertEquals(0L to 100 * 60L, SleepMarkBoundary.adjusted(0, 2 * h, listOf(bed(90 * 60L), wake(100 * 60L))))
    }

    @Test fun theLastBedtimeAndTheFirstWakeWin() =
        assertEquals(1800L to 7 * h, SleepMarkBoundary.adjusted(0, 8 * h,
            listOf(bed(600), bed(1800), wake(7 * h), wake(7 * h + 1800))))

    @Test fun theStoreRoundTripsAndSkipsGarbage() {
        val marks = SleepMarkStore.parse("0:100\nbroken\n1:200\n")
        assertEquals(listOf(bed(100), wake(200)), marks)
    }
}
