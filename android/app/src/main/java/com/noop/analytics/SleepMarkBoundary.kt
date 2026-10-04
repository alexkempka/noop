package com.noop.analytics

/*
 * SleepMarkBoundary — the user's "I'm going to sleep" / "I'm awake" taps as night boundaries.
 * Android-only addition of this fork (no Swift twin; Phase 1 of #461 stays logging-only upstream).
 *
 * Product owner, 04.10.2026: the tap shall be taken as the sleep start, but the app must keep detecting
 * the night by itself when no tap was made. So a mark only ever acts when it exists, and only in the
 * direction the user's statement is certain:
 *
 *  - A BEDTIME tap says "I lay down to sleep now". Sleep cannot have begun before it, so a detected onset
 *    EARLIER than the tap moves to the tap (the couch-TV case of 2.→3.10.). A detected onset later than
 *    the tap is kept — falling asleep takes a while, and the detector saw when.
 *  - A WAKE tap says "I am awake now". Sleep cannot continue after it, so a detected end LATER than the
 *    tap moves to the tap. A detected end earlier than the tap is kept — people tap after getting up.
 *
 * Marks are matched to a night only inside a plausible distance of its detected bounds, so a tap from
 * another night or a nap never reshapes this one. Pure, so the JVM suite covers it.
 */
object SleepMarkBoundary {

    /** A bedtime tap counts for a night whose detected onset lies at most this far before it. */
    const val BEDTIME_REACH_SEC = 3L * 3600
    /** A wake tap counts for a night whose detected end lies at most this far after it. */
    const val WAKE_REACH_SEC = 3L * 3600
    /** Never shrink a night below this — a stray tap must not collapse it. */
    const val MIN_NIGHT_SEC = 60L * 60

    data class Mark(val type: SleepMarkType, val tsSec: Long)

    /** The (start, end) the night should have, or null when the marks change nothing. */
    fun adjusted(startSec: Long, endSec: Long, marks: List<Mark>): Pair<Long, Long>? {
        if (endSec <= startSec) return null
        // The LAST bedtime tap after the detected onset and within reach: the moment the user finally lay down.
        val bedtime = marks.asSequence()
            .filter { it.type == SleepMarkType.BEDTIME && it.tsSec > startSec && it.tsSec - startSec <= BEDTIME_REACH_SEC }
            .maxOfOrNull { it.tsSec }
        // The FIRST wake tap before the detected end and within reach: the moment the user said they were up.
        val wake = marks.asSequence()
            .filter { it.type == SleepMarkType.WAKE && it.tsSec < endSec && endSec - it.tsSec <= WAKE_REACH_SEC }
            .minOfOrNull { it.tsSec }
        var newStart = startSec
        var newEnd = endSec
        if (bedtime != null && endSec - bedtime >= MIN_NIGHT_SEC) newStart = bedtime
        if (wake != null && wake - newStart >= MIN_NIGHT_SEC) newEnd = wake
        return if (newStart == startSec && newEnd == endSec) null else newStart to newEnd
    }
}
