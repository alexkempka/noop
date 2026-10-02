package com.noop.analytics

import com.noop.protocol.LabradorR17

/**
 * The state of one ECG reading as the live screen shows it, folded from the strap's R17 packets.
 * Android-only addition of this fork. Pure, so the JVM suite covers it without a strap.
 *
 * What it deliberately does NOT do: name a rhythm, read the classifier byte, or put a voltage on the
 * curve. `docs/PROTOCOL_ECG.md` leaves the physical scale and the classifier vocabulary unresolved, so
 * amplitude stays in the strap's own units and the classifier result is kept as a number only.
 *
 * The time axis is the one thing the phone can establish itself: samples received over the time they
 * took to arrive. That is a MEASURED rate for this reading, not a documented one, and the screen says so.
 */
data class EcgLiveState(
    val startedAtMs: Long,
    val firstPacketAtMs: Long? = null,
    val lastPacketAtMs: Long? = null,
    /** Every counted sample, in arrival order, capped at [EcgLiveSession.MAX_SAMPLES]. */
    val samples: List<Int> = emptyList(),
    val packets: Int = 0,
    val progress: Int = 0,
    val classifierState: Int = 0,
    val presence: Boolean = false,
    val quality: Int = 0,
    val liveHr: Int = 0,
    val averageHr: Int = 0,
    val variabilityRaw: Int? = null,
    /** The strap's own result code at the end, kept as a number and never interpreted. */
    val classifierRaw: Int = 0,
    val finished: Boolean = false,
    val invalid: Boolean = false,
    /** First moment progress moved above zero — the end of the strap's settling phase. */
    val measuringSinceMs: Long? = null,
    /** First moment contact was reported, so the screen can say how long the strap has been settling. */
    val contactSinceMs: Long? = null,
) {
    val ended: Boolean get() = finished || invalid

    /** Samples per second as received, once there are at least five seconds to measure over. */
    val measuredRate: Double?
        get() {
            val first = firstPacketAtMs ?: return null
            val last = lastPacketAtMs ?: return null
            val span = (last - first) / 1000.0
            if (span < EcgLiveSession.MIN_RATE_SPAN_SEC || samples.size < 2) return null
            return samples.size / span
        }

    enum class Phase { WAITING_FOR_CONTACT, SETTLING, MEASURING, FINISHED, INVALID }

    val phase: Phase
        get() = when {
            invalid -> Phase.INVALID
            finished -> Phase.FINISHED
            progress in 1..99 -> Phase.MEASURING
            presence -> Phase.SETTLING
            else -> Phase.WAITING_FOR_CONTACT
        }
}

object EcgLiveSession {

    /** About three minutes at the rates seen so far; a reading takes well under one. */
    const val MAX_SAMPLES = 40_000

    const val MIN_RATE_SPAN_SEC = 5.0

    /** The strap's completion and abort values, as `Whoop5Ecg` decodes them. */
    private const val PROGRESS_DONE = 100
    private const val PROGRESS_INVALID = 255

    fun start(nowMs: Long) = EcgLiveState(startedAtMs = nowMs)

    fun apply(state: EcgLiveState, packet: LabradorR17, nowMs: Long): EcgLiveState {
        if (state.ended) return state
        val room = MAX_SAMPLES - state.samples.size
        val added = if (room <= 0) state.samples else state.samples + packet.samples.take(room)
        val progress = packet.progress.raw
        val invalid = progress == PROGRESS_INVALID
        val finished = !invalid && (progress == PROGRESS_DONE || (packet.classifierState == 2 && state.measuringSinceMs != null))
        return state.copy(
            firstPacketAtMs = state.firstPacketAtMs ?: nowMs,
            lastPacketAtMs = nowMs,
            samples = added,
            packets = state.packets + 1,
            progress = if (invalid) state.progress else progress,
            classifierState = packet.classifierState,
            presence = packet.presence,
            quality = packet.signalQualityRaw,
            liveHr = packet.liveHR,
            averageHr = packet.averageHR,
            variabilityRaw = packet.variabilityRaw,
            classifierRaw = packet.arrhythmiaCheckResultRaw,
            finished = finished,
            invalid = invalid,
            measuringSinceMs = state.measuringSinceMs ?: if (progress in 1..99) nowMs else null,
            contactSinceMs = when {
                !packet.presence -> null
                else -> state.contactSinceMs ?: nowMs
            },
        )
    }
}
