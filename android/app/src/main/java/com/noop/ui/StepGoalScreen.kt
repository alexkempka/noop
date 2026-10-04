package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The daily step goal — Android-only addition of this fork (no Swift twin).
 *
 * Asked for on 03.10.2026 after the product owner compared the official app's Steps screen. Functionally
 * the same idea (a daily goal against your own recent average); visually our own: a horizontal scale with
 * the last fourteen days as dots, in NOOP's colours, never a vertical fill gauge. The explanation text is
 * written for NOOP and makes no health claims (CLAUDE.md rule 3) — it says what is counted and how.
 */

/** Route key under vital_detail/, like the blood-pressure and ECG screens. */
internal const val STEP_GOAL_KEY = "steps_goal"

internal object StepGoalLogic {
    const val MIN = 1_000
    const val MAX = 30_000
    const val STEP = 500
    const val GRID = 100
    const val WINDOW_DAYS = 30

    /** 0 stays 0 (no goal); anything else snaps to the 100-step grid inside the bounds. */
    fun normalize(goal: Int): Int {
        if (goal <= 0) return 0
        return ((goal / GRID.toDouble()).roundToInt() * GRID).coerceIn(MIN, MAX)
    }

    /** One stepper tap. From "no goal" it starts at the user's rounded average, else at 7,500. */
    fun step(current: Int, up: Boolean, average: Int?): Int {
        val base = if (current > 0) current else normalize(average ?: 7_500)
        if (current <= 0) return base
        return normalize(base + if (up) STEP else -STEP)
    }

    /** The counted days of the trailing window ending [today], oldest first. Days with no count are skipped. */
    fun window(days: List<DailyMetric>, today: LocalDate, n: Int = WINDOW_DAYS): List<Pair<LocalDate, Int>> {
        val from = today.minusDays((n - 1).toLong())
        return days.mapNotNull { d ->
            val date = runCatching { LocalDate.parse(d.day) }.getOrNull() ?: return@mapNotNull null
            val s = d.steps?.takeIf { it > 0 } ?: return@mapNotNull null
            if (date.isBefore(from) || date.isAfter(today)) null else date to s
        }.sortedBy { it.first }
    }

    /** Mean of the counted days, or null with fewer than three (too few to call an average). */
    fun average(window: List<Pair<LocalDate, Int>>): Int? =
        if (window.size < 3) null else window.map { it.second }.average().roundToInt()

    fun daysReached(window: List<Pair<LocalDate, Int>>, goal: Int): Int =
        if (goal <= 0) 0 else window.count { it.second >= goal }

    /** Quick picks: your average, a tenth above it, and two round numbers — distinct, ascending. */
    fun suggestions(average: Int?): List<Int> =
        (listOfNotNull(average?.let(::normalize), average?.let { normalize((normalize(it) * 1.1).roundToInt()) }) +
            listOf(7_500, 10_000)).filter { it in MIN..MAX }.distinct().sorted()
}

/** The goal as every screen reads it (0 = none). Seeded from [ProfileStore] at start-up; written by the view model. */
internal object StepGoalState {
    val goal = kotlinx.coroutines.flow.MutableStateFlow(0)
}

internal fun groupedSteps(n: Int): String = String.format(Locale.getDefault(), "%,d", n)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepGoalScreen(vm: AppViewModel, onClose: (() -> Unit)? = null) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val goal by vm.stepGoal.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val window = remember(days) { StepGoalLogic.window(days, today) }
    val average = remember(window) { StepGoalLogic.average(window) }
    var draft by remember(goal) { mutableIntStateOf(goal) }

    // Fork: the same sky backdrop as the other tile detail screens (VitalDetailScreen), so this page does not
    // read as the one dark-blue screen (product owner, 04.10.2026).
    val backdropContext = LocalContext.current
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(backdropContext) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(backdropContext) }
    ScreenScaffold(
        title = uiText("Step goal"),
        subtitle = uiText("Your own daily target"),
        trailing = onClose?.let { close ->
            {
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = uiString(R.string.l10n_today_screen_close_bbfa773e), tint = Palette.textSecondary)
                }
            }
        },
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        NoopCard(tint = Palette.accent) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Overline(uiText("Goal per day"))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (draft > 0) groupedSteps(draft) else uiText("Not set"),
                        style = NoopType.title1,
                        color = if (draft > 0) Palette.textPrimary else Palette.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    StepperField(
                        value = "",
                        accessibility = uiText("Step goal, %1\$s", if (draft > 0) groupedSteps(draft) else uiText("Not set")),
                        onMinus = { draft = StepGoalLogic.step(draft, up = false, average = average) },
                        onPlus = { draft = StepGoalLogic.step(draft, up = true, average = average) },
                    )
                }
                StepGoalScale(window = window, goal = draft, average = average)
                Text(
                    when {
                        window.isEmpty() -> uiText("No counted days yet in the last 30 days.")
                        average == null -> uiText("Fewer than three counted days so far — no average yet.")
                        else -> uiText("Dots: your last 14 days. Your 30-day average: %1\$s steps.", groupedSteps(average))
                    },
                    style = NoopType.footnote, color = Palette.textSecondary,
                )
                val suggestions = StepGoalLogic.suggestions(average)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    suggestions.forEach { s ->
                        val label = when (s) {
                            average?.let(StepGoalLogic::normalize) -> uiText("Average · %1\$s", groupedSteps(s))
                            else -> groupedSteps(s)
                        }
                        NoopButton(
                            text = label,
                            kind = if (s == draft) NoopButtonKind.Primary else NoopButtonKind.Secondary,
                            onClick = { draft = s },
                        )
                    }
                }
                NoopButton(
                    text = uiText("Save goal"),
                    fullWidth = true,
                    enabled = draft != goal,
                    onClick = { vm.setStepGoal(draft) },
                )
                if (goal > 0) {
                    TextButton(onClick = { vm.setStepGoal(0) }) {
                        Text(uiText("Remove goal"), style = NoopType.footnote, color = Palette.textSecondary)
                    }
                }
                if (goal > 0 && window.isNotEmpty()) {
                    Text(
                        uiText("Reached on %1\$s of %2\$s counted days.", StepGoalLogic.daysReached(window, goal), window.size),
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                }
            }
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Overline(uiText("About steps"))
                Text(uiText("How much you move in a day"), style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    uiText("Steps add up everything you walk in a day — the way to work, the stairs, the round with the dog — including days without any training. They are a simple, everyday measure of how much you are on your feet."),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
                Text(
                    uiText("The count comes from the motion sensor in your strap. NOOP reads the strap's own counter and adds it up per day; if it counts more than you actually walk, you can correct it under More → Settings → Profile → Step calibration."),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
                Text(
                    uiText("A goal shows at a glance whether you keep to the level you set yourself. Which number suits you is your choice — NOOP gives no advice on it. Over weeks, the history shows how your everyday movement changes, and how it sits next to your Effort and your Rest."),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
            }
        }
    }
}

/**
 * A horizontal scale from 0 to a rounded top: the last fourteen counted days as dots (filled when the
 * goal was reached), a solid marker for the goal and a dashed marker for the 30-day average.
 */
@Composable
private fun StepGoalScale(window: List<Pair<LocalDate, Int>>, goal: Int, average: Int?) {
    val recent = window.takeLast(14)
    val top = (listOfNotNull(goal.takeIf { it > 0 }, average, recent.maxOfOrNull { it.second }, 10_000).max() * 1.15)
        .let { ((it / 5_000.0).toInt() + 1) * 5_000 }
    val track = Palette.textTertiary
    val goalColor = Palette.accent
    val avgColor = Palette.metricAmber
    val dotOff = Palette.textSecondary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val w = size.width
            val y = size.height * 0.7f
            fun x(v: Int) = (v.toFloat() / top) * w
            drawRoundRect(track.copy(alpha = 0.35f), topLeft = Offset(0f, y - 2.dp.toPx()),
                size = Size(w, 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
            // Days: dots stacked a little apart in time order so equal counts do not hide each other.
            recent.forEachIndexed { i, (_, s) ->
                val reached = goal > 0 && s >= goal
                val cy = y - 10.dp.toPx() - (i % 3) * 7.dp.toPx()
                drawCircle(if (reached) goalColor else dotOff.copy(alpha = 0.7f), radius = 3.5.dp.toPx(), center = Offset(x(s), cy))
            }
            average?.let { a ->
                drawLine(avgColor, Offset(x(a), y - 30.dp.toPx()), Offset(x(a), y + 10.dp.toPx()),
                    strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            }
            if (goal > 0) {
                val gx = x(goal)
                drawLine(goalColor, Offset(gx, y - 34.dp.toPx()), Offset(gx, y + 12.dp.toPx()), strokeWidth = 3.dp.toPx())
                val p = Path().apply {
                    moveTo(gx, y + 12.dp.toPx()); lineTo(gx - 6.dp.toPx(), y + 20.dp.toPx()); lineTo(gx + 6.dp.toPx(), y + 20.dp.toPx()); close()
                }
                drawPath(p, goalColor)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", style = NoopType.footnote, color = Palette.textTertiary, modifier = Modifier.weight(1f))
            Text(groupedSteps(top), style = NoopType.footnote, color = Palette.textTertiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            if (goal > 0) Text("▲ " + uiText("Goal"), style = NoopType.footnote, color = goalColor)
            if (average != null) Text("┆ " + uiText("Average"), style = NoopType.footnote, color = avgColor)
        }
    }
}

/** The entry at the top of the Steps history: the current goal, one tap to change it. */
@Composable
internal fun StepGoalEntryCard(goal: Int, onClick: () -> Unit) {
    NoopCard(modifier = Modifier.clickable(onClick = onClick), tint = Palette.accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Column(Modifier.weight(1f)) {
                Text(uiText("Step goal"), style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    if (goal > 0) uiText("%1\$s steps per day", groupedSteps(goal)) else uiText("Not set — tap to choose one"),
                    style = NoopType.footnote, color = Palette.textSecondary,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.textTertiary)
        }
    }
}
