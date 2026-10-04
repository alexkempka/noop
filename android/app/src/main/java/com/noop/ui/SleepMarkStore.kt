package com.noop.ui

import android.content.Context
import com.noop.analytics.SleepMark
import com.noop.analytics.SleepMarkBoundary
import com.noop.analytics.SleepMarkType

/**
 * The exact instants of the user's sleep-mark taps. Android-only addition of this fork.
 *
 * The shared `sleep_mark` series keeps one value per calendar day and no time of day (its natural key is
 * the day), so the instant only ever reached the strap log. [SleepMarkBoundary] needs the instant, so the
 * last [KEEP] taps are kept here as "type:epochSeconds" lines. The series row is still written as before.
 */
internal object SleepMarkStore {
    private const val PREFS = "noop_sleep_marks"
    private const val KEY = "marks"
    /** Nights whose bounds this fork moved from a mark; such a night may be moved again by a later tap. */
    private const val KEY_APPLIED = "applied_nights"
    const val KEEP = 60

    fun record(context: Context, mark: SleepMark) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lines = (prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } +
            "${mark.type.seriesValue}:${mark.tsMs / 1000L}").takeLast(KEEP)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun all(context: Context): List<SleepMarkBoundary.Mark> = parse(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty(),
    )

    internal fun parse(raw: String): List<SleepMarkBoundary.Mark> = raw.lines().mapNotNull { line ->
        val parts = line.trim().split(':')
        if (parts.size != 2) return@mapNotNull null
        val type = parts[0].toIntOrNull() ?: return@mapNotNull null
        val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
        SleepMarkBoundary.Mark(SleepMarkType.fromSeriesValue(type.toDouble()), ts)
    }

    fun wasApplied(context: Context, nightKey: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_APPLIED, emptySet()).orEmpty().contains(nightKey)

    fun markApplied(context: Context, nightKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_APPLIED, emptySet()).orEmpty().toMutableSet().apply { add(nightKey) }
        prefs.edit().putStringSet(KEY_APPLIED, set.toList().takeLast(KEEP).toSet()).apply()
    }
}
