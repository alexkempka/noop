package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

/*
 * The user's own sleep goal — Android-only addition of this fork (no Swift twin).
 *
 * NOOP measures every night against an automatic need that never drops below 8 h for an adult. Some
 * people settle on a different nightly goal; after a week of nights the Sleep tab asks once whether the
 * automatic need should stay, and the answer can be changed later under Settings → Profile. A set goal
 * replaces the automatic need in Rest, debt and "needed" (`RestScorer.userSleepGoalHours`).
 *
 * The copy gives no advice on how much sleep is right: the choice is the user's, NOOP only measures.
 */

/** Recorded nights before the question appears — the same week the automatic need waits for. */
internal const val SLEEP_GOAL_ASK_AFTER_NIGHTS = 7

/** Starting value when the user chooses to set a goal and none is stored yet. */
internal const val SLEEP_GOAL_SEED_MIN = 7 * 60

/** "7:30 h" — hours and minutes, the same in every language this app ships. */
internal fun formatSleepGoal(minutes: Int): String = "%d:%02d h".format(minutes / 60, minutes % 60)

internal fun formatSleepGoalHours(hours: Double): String = formatSleepGoal((hours * 60.0).roundToInt())

/** One 15-minute step, kept inside the bounds the store accepts. */
internal fun stepSleepGoal(current: Int, up: Boolean): Int {
    val base = if (current <= 0) SLEEP_GOAL_SEED_MIN else current
    return ProfileStore.normalizeSleepGoal(base + if (up) ProfileStore.SLEEP_GOAL_STEP_MIN else -ProfileStore.SLEEP_GOAL_STEP_MIN)
}

/** True when the one-time question should show: not answered yet and a week of recorded nights. */
internal fun shouldAskSleepGoal(asked: Boolean, recordedNights: Int): Boolean =
    !asked && recordedNights >= SLEEP_GOAL_ASK_AFTER_NIGHTS

@Composable
internal fun SleepGoalQuestionCard(
    currentNeedHours: Double,
    onKeep: () -> Unit,
    onSet: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(SLEEP_GOAL_SEED_MIN) }
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(title = uiText("Your sleep goal"))
            Text(
                uiText(
                    "NOOP currently measures each night against %1\$s of sleep. If you aim for a different amount, set your own goal: sleep score, sleep debt and “needed” are then calculated against it.",
                    formatSleepGoalHours(currentNeedHours),
                ),
                style = NoopType.subhead, color = Palette.textSecondary,
            )
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(uiText("Goal per night"), style = NoopType.subhead, color = Palette.textPrimary,
                        modifier = Modifier.weight(1f))
                    StepperField(
                        value = formatSleepGoal(draft),
                        accessibility = uiText("Sleep goal, %1\$s", formatSleepGoal(draft)),
                        onMinus = { draft = stepSleepGoal(draft, up = false) },
                        onPlus = { draft = stepSleepGoal(draft, up = true) },
                    )
                }
                // Stacked full width: side by side, the German labels were cut off on a phone.
                NoopButton(text = uiText("Save goal"), fullWidth = true, onClick = { onSet(draft) })
                NoopButton(text = uiText("Cancel"), kind = NoopButtonKind.Secondary, fullWidth = true,
                    onClick = { editing = false })
            } else {
                NoopButton(text = uiText("Set my own"), fullWidth = true, onClick = { editing = true })
                NoopButton(text = uiText("Keep %1\$s", formatSleepGoalHours(currentNeedHours)),
                    kind = NoopButtonKind.Secondary, fullWidth = true, onClick = onKeep)
            }
            Text(
                uiText("You can change it any time under More → Settings → Profile."),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

/** The Settings → Profile row: automatic, or the user's goal on a 15-minute stepper. */
@Composable
internal fun SleepGoalSettingsRow(goalMinutes: Int, onChange: (Int) -> Unit) {
    SettingsFormRow(label = uiText("Sleep goal")) {
            StepperField(
                value = if (goalMinutes > 0) formatSleepGoal(goalMinutes) else uiText("Automatic"),
                accessibility = if (goalMinutes > 0) {
                    uiText("Sleep goal, %1\$s", formatSleepGoal(goalMinutes))
                } else {
                    uiText("Sleep goal, automatic")
                },
                valueColor = if (goalMinutes > 0) Palette.textPrimary else Palette.textTertiary,
                onMinus = { onChange(stepSleepGoal(goalMinutes, up = false)) },
                onPlus = { onChange(stepSleepGoal(goalMinutes, up = true)) },
            )
    }
    Text(
        text = if (goalMinutes > 0) {
            uiText("Your own goal. Sleep score, sleep debt and “needed” are measured against it.")
        } else {
            uiText("Automatic: NOOP’s own need, never below 8 h for an adult.")
        },
        style = NoopType.footnote,
        color = if (goalMinutes > 0) Palette.accent else Palette.textTertiary,
    )
    if (goalMinutes > 0) {
        TextButton(onClick = { onChange(0) }) {
            Text(uiText("Back to automatic"), style = NoopType.footnote, color = Palette.accent)
        }
    }
}
