package com.noop.ui

import com.noop.analytics.LiveSessionEngine
import com.noop.data.LiveSessionRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live Session has to stop on its own.
 *
 * It already did when the STRAP went away — ten minutes of a stale stream and the guardian bows out.
 * The case that was missing is the opposite one, and it is the one a wearer actually hit: strap on,
 * stream perfectly healthy, sitting still below the band, so every 50-second nudge landed and none of
 * them was answered. That session ran 72 minutes and sent 162 buzzes, and nothing in it would ever
 * have stopped.
 *
 * These tests pin the new rule and, just as importantly, pin that it cannot cut short a session that
 * IS being followed: one second in the band resets the count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionAutoEndTest {

    private val rhr = 55.0
    private val hrMax = 190.0
    private val config = LiveSessionEngine.Config(rhr, hrMax, charge = 41.0)
    private val band = LiveSessionEngine.band(config)

    /** Comfortably under the floor — the "sitting still" case the rule exists for. */
    private val belowBpm = (band.floorBpm - 25).toInt()

    /** Inside the band — a wearer doing what the coach asked. */
    private val inBandBpm = ((band.floorBpm + band.ceilingBpm) / 2).toInt()

    private class Harness {
        var now = 1_000_000L
        val buzzes = mutableListOf<Int>()
        val rows = mutableListOf<LiveSessionRow>()
        var bpm: Int? = null
        val visible = mutableListOf<LiveSessionRunner.Snapshot>()
    }

    private fun TestScope.runner(h: Harness) = LiveSessionRunner(
        config = LiveSessionEngine.Config(55.0, 190.0, charge = 41.0),
        deviceId = "test-strap",
        scope = this,
        readBpm = { h.bpm },
        buzz = { loops -> h.buzzes += loops },
        persist = { row -> h.rows += row },
        realtimeHr = { },
        onVisibleChange = { snap -> h.visible += snap },
        nowEpochSec = { h.now },
    )

    /** Run the 1 Hz tick forward [seconds] wall-clock seconds. */
    private fun TestScope.elapse(h: Harness, seconds: Int) {
        repeat(seconds) {
            h.now += 1
            advanceTimeBy(1_000L)
        }
    }

    // ── The rule ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a session nobody answers ends itself`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.bpm = belowBpm
        val r = runner(h)
        LiveSessionRunner.begin(r)
        try {
            // Twelve minutes of sitting still. At a 50-second cooldown that is well past the ten
            // unanswered cues the rule allows.
            elapse(h, 12 * 60)
            assertTrue("the session should have ended itself", r.snapshot.value.ended)
            assertTrue("and it should say it was automatic", r.snapshot.value.endedAutomatically)
            assertTrue(
                "it must stop well short of the 162 buzzes the real session sent",
                h.buzzes.size < 40,
            )
        } finally {
            r.end(); LiveSessionRunner.clear(r)
        }
    }

    @Test
    fun `a session that is being followed is never cut short`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.bpm = inBandBpm
        val r = runner(h)
        LiveSessionRunner.begin(r)
        try {
            // The same twelve minutes, but inside the band the whole way: no cue is sent, nothing goes
            // unanswered, and the session must still be running.
            elapse(h, 12 * 60)
            assertFalse("a followed session must keep running", r.snapshot.value.ended)
        } finally {
            r.end(); LiveSessionRunner.clear(r)
        }
    }

    @Test
    fun `answering the coach resets the count`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val r = runner(h)
        LiveSessionRunner.begin(r)
        try {
            // Six minutes ignored — enough cues to be on the way to the limit, not past it.
            h.bpm = belowBpm
            elapse(h, 6 * 60)
            assertFalse("six minutes alone must not end it", r.snapshot.value.ended)
            // One minute of doing what was asked.
            h.bpm = inBandBpm
            elapse(h, 60)
            // Then six more ignored. Without the reset the two stretches together would trip the limit;
            // with it, the second stretch starts from zero and the session survives.
            h.bpm = belowBpm
            elapse(h, 6 * 60)
            assertFalse("the in-band minute must have reset the count", r.snapshot.value.ended)
        } finally {
            r.end(); LiveSessionRunner.clear(r)
        }
    }

    // ── What the notification is told ──────────────────────────────────────────────────────────────

    @Test
    fun `the session reports itself at start and at end`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.bpm = inBandBpm
        val r = runner(h)
        LiveSessionRunner.begin(r)
        elapse(h, 5)
        assertTrue("a running session must announce itself", h.visible.isNotEmpty())
        assertFalse("and not as an ended one", h.visible.first().ended)
        r.end()
        assertTrue("the end must be announced too, so the notification comes down", h.visible.last().ended)
        LiveSessionRunner.clear(r)
    }

    @Test
    fun `the limits are the documented ones`() {
        // Pinned so a later edit to either constant has to come through a test that says why.
        assertEquals(10, LiveSessionRunner.AUTO_END_AFTER_UNANSWERED_CUES)
        assertEquals(600, LiveSessionRunner.AUTO_END_AFTER_STALE_SEC)
        assertEquals(4 * 60 * 60, LiveSessionRunner.AUTO_END_AFTER_SEC)
    }
}
