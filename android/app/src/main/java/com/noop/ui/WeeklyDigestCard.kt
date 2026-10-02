package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.BalanceRead
import com.noop.analytics.RestScorer
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyMetric
import com.noop.analytics.WeeklyMetricSummary
import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Weekly Digest (#208)
//
// A deterministic, offline "week in review". Kotlin parity for the macOS/iOS
// WeeklyDigestView. Reads the merged daily history from the view model, pulls each
// tracked metric into a "yyyy-MM-dd"→value map, and feeds the pure
// WeeklyDigestEngine to produce a Monday-anchored summary: per-metric this-week
// mean + week-over-week delta + vs-baseline, the biggest movers, a strain-vs-recovery
// balance read, and 1–2 plain-English focal points. No AI, no network.
//
// Two surfaces are exposed so navigation can wire whichever it wants:
//   • WeeklyDigestCard  — an embeddable card (drop into Today / Trends).
//   • WeeklyDigestScreen — a full ScreenScaffold screen (for a nav destination).
// Both share WeeklyDigestContent so they never drift. Framing is informational
// (non-clinical), consistent with the app disclaimer.

/**
 * The engine's Effort display factor for the user's scale (#268/#463): moverSentence's
 * "(avg X vs Y)" prints stored 0-100 Effort means, so the 0-21 toggle rescales them for
 * display only. 1.0 leaves every sentence byte-identical to the pre-toggle output.
 */
internal fun effortDisplayFactor(scale: EffortScale): Double =
    if (scale == EffortScale.WHOOP) UnitFormatter.EFFORT_SCALE_FACTOR else 1.0

/**
 * Build the weekly digest for the week containing today's logical local day from a
 * [DailyMetric] history. Extracts each metric into a day→value map and hands it to the
 * pure engine. [effortDisplayFactor] follows the Effort display-scale toggle so the
 * engine's focal sentences quote Effort on the scale the user reads everywhere else.
 */
fun buildWeeklyDigest(
    days: List<DailyMetric>,
    anchorDay: String = logicalDayKeyNow(),
    effortDisplayFactor: Double = 1.0,
): WeeklyDigest {
    val charge = HashMap<String, Double>()
    val effort = HashMap<String, Double>()
    val rest = HashMap<String, Double>()
    val rhr = HashMap<String, Double>()
    val hrv = HashMap<String, Double>()
    for (d in days) {
        d.recovery?.let { charge[d.day] = it }
        d.strain?.let { effort[d.day] = it }
        // Rest = the sleep-performance composite recomputed on the persisted day.
        RestScorer.restFromDaily(d)?.let { rest[d.day] = it }
        d.restingHr?.let { rhr[d.day] = it.toDouble() }
        d.avgHrv?.let { hrv[d.day] = it }
    }
    return WeeklyDigestEngine.build(
        byMetric = mapOf(
            WeeklyMetric.CHARGE to charge,
            WeeklyMetric.EFFORT to effort,
            WeeklyMetric.REST to rest,
            WeeklyMetric.RHR to rhr,
            WeeklyMetric.HRV to hrv,
        ),
        anchorDay = anchorDay,
        effortDisplayFactor = effortDisplayFactor,
    )
}

// MARK: - Embeddable card

/**
 * The weekly digest as a single card (for Today / Trends). Renders nothing when there's
 * no data this week, so it's safe to always place.
 */
@Composable
fun WeeklyDigestCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val factor = effortDisplayFactor(UnitPrefs.effortScale(LocalContext.current))
    val digest = buildWeeklyDigest(days, effortDisplayFactor = factor)
    if (digest.isEmpty) return
    NoopCard(modifier = modifier) {
        WeeklyDigestContent(digest = digest, compact = true)
    }
}

// MARK: - Full screen

/** The weekly digest as a full screen (for a nav destination). */
@Composable
fun WeeklyDigestScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val factor = effortDisplayFactor(UnitPrefs.effortScale(LocalContext.current))
    ScreenScaffold(title = uiString(R.string.l10n_weekly_digest_card_week_in_review_66d95a07), subtitle = uiString(R.string.l10n_weekly_digest_card_your_monday_to_sunday_read_in_c475888a)) {
        val digest = buildWeeklyDigest(days, effortDisplayFactor = factor)
        if (digest.isEmpty) {
            DataPendingNote(
                title = uiString(R.string.l10n_weekly_digest_card_no_readings_this_week_yet_0745a2df),
                body = uiString(R.string.l10n_weekly_digest_card_wear_your_strap_or_import_your_b3211f76),
            )
        } else {
            NoopCard { WeeklyDigestContent(digest = digest, compact = false) }
        }
    }
}

// MARK: - Shared content

private val MONTHS = arrayOf(
    "Jan", "Feb", uiText("Mar"), "Apr", uiText("May"), "Jun", "Jul", "Aug", "Sep", uiText("Oct"), "Nov", uiText("Dec"),
)

private val DISPLAY_ORDER = listOf(
    WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST, WeeklyMetric.HRV, WeeklyMetric.RHR,
)

/**
 * The inner content shared by the card and the full screen. [compact] trims the metric
 * grid to the headline rows for the card; the full screen shows everything plus a footer.
 */
@Composable
fun WeeklyDigestContent(digest: WeeklyDigest, compact: Boolean = false) {
    // #268/#463: the Effort row follows the Effort display-scale toggle like every other Effort
    // read-out in the app (Swift's DigestScoreCard already does). Read once here, threaded to the
    // rows, so a 0-21 user can't see "Effort 22" beside a Trends chart reading 4.6.
    val effortScale = UnitPrefs.effortScale(LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Header.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline(uiText("Week in review"))
                Text(weekRangeLabel(digest), style = NoopType.title2, color = Palette.textPrimary)
            }
            Text(
                uiString(R.string.l10n_weekly_digest_card_digest_dayswithdata_7_days_182e6a18, digest.daysWithData),
                style = NoopType.footnote,
                color = Palette.textSecondary,
                modifier = Modifier.semantics {
                    contentDescription = uiString(R.string.l10n_weekly_digest_card_digest_dayswithdata_of_7_days_had_8068f0ee, digest.daysWithData)
                },
            )
        }

        // Focal points — the plain-English read, most salient first.
        if (digest.focalPoints.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                digest.focalPoints.forEach { FocalRow(localizedDigestLine(it)) }
            }
        }

        HorizontalDivider(color = Palette.hairline)

        // Per-metric rows.
        val rows = (if (compact) listOf(WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST)
        else DISPLAY_ORDER).mapNotNull { digest.summary(it) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { MetricRow(it, effortScale) }
        }

        if (!compact) {
            HorizontalDivider(color = Palette.hairline)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                digest.sleepConsistencySD?.let { sd ->
                    Text(
                        uiString(R.string.l10n_weekly_digest_card_sleep_steadiness_rest_varied_fmt1_sd_44997f21, fmt1(sd)),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Text(uiText(digest.balance.sentence), style = NoopType.footnote, color = Palette.textTertiary)
                Text(
                    uiString(R.string.l10n_weekly_digest_card_informational_only_not_medical_advice_593feb77),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun FocalRow(line: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Palette.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(line, style = NoopType.subhead, color = Palette.textPrimary)
    }
}

@Composable
private fun MetricRow(s: WeeklyMetricSummary, effortScale: EffortScale) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowAccessibility(s, effortScale) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            weeklyMetricLabel(s.metric),
            style = NoopType.subhead,
            color = Palette.textSecondary,
            maxLines = 1,
            modifier = Modifier.width(112.dp),
        )
        Text(
            meanText(s, effortScale),
            style = NoopType.bodyNumber,
            color = Palette.textPrimary,
            // 84 (was 64) so the Effort scale read-out ("21.6 / 100") fits on one line.
            modifier = Modifier.width(84.dp),
        )
        Spacer(Modifier.weight(1f))
        DeltaChip(s)
    }
}

@Composable
private fun DeltaChip(s: WeeklyMetricSummary) {
    val tone = chipTone(s)
    val arrow: ImageVector = when {
        s.wowDelta > 0 -> Icons.Filled.ArrowUpward
        s.wowDelta < 0 -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.Remove
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(Metrics.cornerPill))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clearAndSetSemantics { },
    ) {
        Icon(arrow, contentDescription = null, tint = tone, modifier = Modifier.size(10.dp))
        Text(deltaText(s), style = NoopType.captionNumber, color = tone)
    }
}

// MARK: - Formatting

private fun weekRangeLabel(digest: WeeklyDigest): String {
    // In the app language ("28. Sept. – 4. Okt."); the English month table below only as a fallback.
    val fmt = java.time.format.DateTimeFormatter.ofPattern(uiText("MMM d"), java.util.Locale.getDefault())
    val a = runCatching { java.time.LocalDate.parse(digest.weekStart).format(fmt) }.getOrNull()
    val b = runCatching { java.time.LocalDate.parse(digest.weekEnd).format(fmt) }.getOrNull()
    return if (a != null && b != null) "$a – $b" else "${shortDate(digest.weekStart)}-${shortDate(digest.weekEnd)}"
}

/** On-screen name of a digest metric; the engine's English label stays its key and test value. */
internal fun weeklyMetricLabel(metric: WeeklyMetric): String = when (metric) {
    WeeklyMetric.CHARGE -> uiText("Charge")
    WeeklyMetric.EFFORT -> uiText("Effort")
    WeeklyMetric.REST -> uiText("Sleep quality")
    WeeklyMetric.RHR -> uiText("Resting HR")
    WeeklyMetric.HRV -> "HRV"
}

/**
 * The engine's focal sentences (pure Kotlin, byte-identical to the Swift twin and tested in English)
 * re-read for the screen: each known sentence shape is matched and rebuilt from the language's own
 * template. An unknown shape is shown as it came.
 */
internal fun localizedDigestLine(line: String): String {
    val labelOf = { english: String -> WeeklyMetric.values().firstOrNull { it.label == english }?.let(::weeklyMetricLabel) ?: english }
    Regex("""^(.+) is (up|down|flat) (\S+?)( pts| bpm| ms)? week over week \(avg (-?\d+) vs (-?\d+)\)(, a good sign|, worth a look)?\.$""")
        .matchEntire(line)?.let { m ->
            val (label, dir, mag, pts, a, b, frame) = m.destructured
            val magnitude = if (pts == " pts") uiText("%1\$s pts", mag) else mag + pts
            val frameText = if (frame.isEmpty()) "" else uiText(frame)
            return uiText("%1\$s is %2\$s %3\$s week over week (avg %4\$s vs %5\$s)%6\$s.", labelOf(label), uiText(dir), magnitude, a, b, frameText)
        }
    Regex("""^Only (\d+) (day|days) into this week so far, too early to call a week-over-week trend yet\.$""").matchEntire(line)?.let { m ->
        return uiText("Only %1\$s day(s) into this week so far, too early to call a week-over-week trend yet.", m.groupValues[1])
    }
    Regex("""^Last week only had (\d+) (day|days) of data, so week-over-week changes are rough, not a trend\.$""").matchEntire(line)?.let { m ->
        return uiText("Last week only had %1\$s day(s) of data, so week-over-week changes are rough, not a trend.", m.groupValues[1])
    }
    Regex("""^A steady week: Rest held even \(±([\d.]+) pts\) and nothing moved much\.$""").matchEntire(line)?.let { m ->
        return uiText("A steady week: Rest held even (±%1\$s pts) and nothing moved much.", m.groupValues[1])
    }
    return uiText(line)
}

/** "Jun 8" from "2026-06-08", via the engine's own pure parse (no Calendar). */
private fun shortDate(ymd: String): String {
    val p = WeeklyDigestEngine.parseYMD(ymd) ?: return ymd
    val name = if (p[1] in 1..12) MONTHS[p[1] - 1] else p[1].toString()
    return "$name ${p[2]}"
}

internal fun meanText(s: WeeklyMetricSummary, effortScale: EffortScale): String {
    if (s.thisWeek.n == 0) return "—"
    // #463: Effort is STORED 0-100; render it on the user's chosen display scale WITH the denominator
    // ("4.6 / 21", "21.6 / 100") so the card can't read as a different number than the Trends chart.
    if (s.metric == WeeklyMetric.EFFORT) {
        return "${UnitFormatter.effortDisplay(s.thisWeek.mean, effortScale)} / " +
            UnitFormatter.effortScaleMax(effortScale)
    }
    val v = s.thisWeek.mean.roundToInt()
    return if (s.metric.unit.isEmpty()) "$v" else "$v ${s.metric.unit}"
}

internal fun deltaText(s: WeeklyMetricSummary): String {
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) return "new"
    val pct = s.weekOverWeek.pctChange
    // Sub-1% (or unpercentable) moves read "<1%", matching Swift. The old fallback printed the raw
    // points delta: a bare "0.1", and for Effort a stored 0-100 figure the scale toggle never saw.
    return if (pct != null && abs(pct) >= 1) "${abs(pct).roundToInt()}%" else "<1%"
}

/**
 * Tone: good moves green, bad moves rose, flat/uncomparable grey — folding in each
 * metric's higherIsBetter (so a Resting-HR rise reads as a warning). A ROUGH comparison
 * (either side thin, engine's [WeeklyMetricSummary.isRoughComparison], the deferred half of
 * the 4.2.10 fix for #463) keeps its arrow + % but stays grey regardless of direction.
 */
private fun chipTone(s: WeeklyMetricSummary): Color = when {
    s.isRoughComparison -> Palette.textTertiary
    s.wowGoodness == 1 -> Palette.statusPositive
    s.wowGoodness == -1 -> Palette.statusCritical
    else -> Palette.textTertiary
}

private fun rowAccessibility(s: WeeklyMetricSummary, effortScale: EffortScale): String {
    val mean = meanText(s, effortScale)
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) {
        return uiText("%1\$s: %2\$s this week, no comparison.", weeklyMetricLabel(s.metric), mean)
    }
    val dir = if (s.wowDelta > 0) uiText("up") else if (s.wowDelta < 0) uiText("down") else uiText("unchanged")
    // A rough comparison drops the verdict framing too, so VoiceOver/TalkBack matches the neutral chip.
    val frame = when {
        s.isRoughComparison -> ""
        s.wowGoodness == 1 -> uiText(", a good sign")
        s.wowGoodness == -1 -> uiText(", worth a look")
        else -> ""
    }
    return uiText("%1\$s: %2\$s this week, %3\$s %4\$s week over week%5\$s.", weeklyMetricLabel(s.metric), mean, dir, deltaText(s), frame)
}

private fun fmt1(x: Double): String = ((x * 10).roundToInt() / 10.0).toString()
