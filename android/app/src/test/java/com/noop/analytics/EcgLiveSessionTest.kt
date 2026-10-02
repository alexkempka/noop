package com.noop.analytics

import com.noop.protocol.EcgHeartKeyProgress
import com.noop.protocol.EcgLabradorFlags
import com.noop.protocol.EcgSignalQuality
import com.noop.protocol.EcgUnreadableMask
import com.noop.protocol.LabradorR17
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgLiveSessionTest {

    private fun packet(progress: Int, state: Int = 1, presence: Boolean = true, samples: Int = 100, hr: Int = 0) =
        LabradorR17(
            packetType = 43,
            headerSecondary = 0,
            sequence = 0,
            strapSeconds = 0,
            subseconds = 0,
            signalQuality = EcgSignalQuality.UNKNOWN,
            signalQualityRaw = 3,
            flags = EcgLabradorFlags(if (presence) 0x08 else 0),
            arrhythmiaCheckResult = null,
            arrhythmiaCheckResultRaw = 7,
            classifierState = state,
            progress = EcgHeartKeyProgress(progress),
            unreadable = EcgUnreadableMask(0),
            averageHR = hr,
            liveHR = hr,
            variabilityRaw = null,
            reserved = 0,
            sampleCount = samples,
            samples = List(samples) { it },
            tail = emptyList(),
        )

    @Test
    fun aReadingWalksThroughContactSettlingMeasuringAndDone() {
        var s = EcgLiveSession.start(0)
        assertEquals(EcgLiveState.Phase.WAITING_FOR_CONTACT, s.phase)
        s = EcgLiveSession.apply(s, packet(progress = 0, presence = false), 1_000)
        assertEquals(EcgLiveState.Phase.WAITING_FOR_CONTACT, s.phase)
        s = EcgLiveSession.apply(s, packet(progress = 0), 2_000)
        assertEquals(EcgLiveState.Phase.SETTLING, s.phase)
        assertEquals(2_000L, s.contactSinceMs)
        // The strap's 6–10 s of settling: still contact, still no progress.
        s = EcgLiveSession.apply(s, packet(progress = 0), 9_000)
        assertEquals(EcgLiveState.Phase.SETTLING, s.phase)
        s = EcgLiveSession.apply(s, packet(progress = 3, hr = 75), 10_000)
        assertEquals(EcgLiveState.Phase.MEASURING, s.phase)
        assertEquals(10_000L, s.measuringSinceMs)
        s = EcgLiveSession.apply(s, packet(progress = 100, state = 2, hr = 78), 48_000)
        assertTrue(s.finished)
        assertEquals(EcgLiveState.Phase.FINISHED, s.phase)
        assertEquals(78, s.averageHr)
        assertEquals(7, s.classifierRaw)
    }

    @Test
    fun theInvalidSentinelEndsTheReadingWithoutCountingAsProgress() {
        var s = EcgLiveSession.apply(EcgLiveSession.start(0), packet(progress = 40), 1_000)
        s = EcgLiveSession.apply(s, packet(progress = 255), 2_000)
        assertTrue(s.invalid)
        assertFalse(s.finished)
        assertEquals(40, s.progress)
    }

    @Test
    fun packetsAfterTheEndChangeNothing() {
        var s = EcgLiveSession.apply(EcgLiveSession.start(0), packet(progress = 100, state = 2), 1_000)
        val n = s.samples.size
        s = EcgLiveSession.apply(s, packet(progress = 100, state = 2), 2_000)
        assertEquals(n, s.samples.size)
    }

    @Test
    fun aStateTwoBeforeAnyMeasuringIsNotTakenAsDone() {
        val s = EcgLiveSession.apply(EcgLiveSession.start(0), packet(progress = 0, state = 2), 1_000)
        assertFalse(s.finished)
    }

    @Test
    fun theTimeAxisIsMeasuredOnlyOverEnoughTime() {
        var s = EcgLiveSession.start(0)
        s = EcgLiveSession.apply(s, packet(progress = 0), 1_000)
        s = EcgLiveSession.apply(s, packet(progress = 0), 3_000)
        assertNull(s.measuredRate)
        s = EcgLiveSession.apply(s, packet(progress = 5), 11_000)
        assertNotNull(s.measuredRate)
        assertEquals(30.0, s.measuredRate!!, 1e-9) // 300 samples over 10 s
    }

    @Test
    fun samplesAreCapped() {
        var s = EcgLiveSession.start(0)
        repeat(EcgLiveSession.MAX_SAMPLES / 100 + 5) { s = EcgLiveSession.apply(s, packet(progress = 10), it * 1_000L) }
        assertEquals(EcgLiveSession.MAX_SAMPLES, s.samples.size)
    }
}
