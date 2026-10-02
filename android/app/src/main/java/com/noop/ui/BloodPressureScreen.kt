package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.BloodPressureEstimator
import com.noop.analytics.BloodPressureEstimator.CuffReading
import com.noop.analytics.BloodPressureEstimator.NightFeatures
import com.noop.analytics.LabBookProjection
import com.noop.analytics.LabMarkerCategory
import com.noop.data.DailyMetric
import com.noop.data.LabMarkerRow
import com.noop.protocol.ppgWaveformAbsolute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** Route key under vital_detail/, so the screen needs no navigation change of its own. */
internal const val BLOOD_PRESSURE_KEY = "blood_pressure"

/** Cuff readings live in the Lab Book under the same device id its editor uses. */
private const val CUFF_DEVICE_ID = "my-whoop"

/** How far back nights are read; the pulse waveform itself is only retained for about a week. */
private const val NIGHTS_BACK_DAYS = 120L
private const val PULSE_BACK_DAYS = 10L

/**
 * The blood-pressure ESTIMATE, its cuff calibration and its measured accuracy. Android-only addition of
 * this fork. The product owner asked for WHOOP's Blood Pressure Insights explicitly (02.10.2026); every
 * number here is labelled as an estimate, and nothing classifies a value.
 */
@Composable
fun BloodPressureScreen(vm: AppViewModel, onClose: (() -> Unit)? = null) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }
    var assessment by remember { mutableStateOf<BloodPressureEstimator.Assessment?>(null) }
    var nightsRead by remember { mutableStateOf<List<NightFeatures>>(emptyList()) }
    LaunchedEffect(days, reload) {
        val nights = loadNights(vm, days)
        val readings = loadCuffReadings(vm)
        nightsRead = nights
        assessment = BloodPressureEstimator.assess(readings, nights, System.currentTimeMillis() / 1000L)
    }
    val scope = rememberCoroutineScope()

    ScreenScaffold(
        title = uiString(R.string.bp_title),
        subtitle = uiString(R.string.bp_subtitle),
        trailing = onClose?.let { close ->
            {
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = uiString(R.string.l10n_today_screen_close_bbfa773e), tint = Palette.textSecondary)
                }
            }
        },
    ) {
        val a = assessment
        if (a == null) {
            DataPendingNote(title = uiString(R.string.bp_loading_title), body = uiString(R.string.bp_loading_body))
            return@ScreenScaffold
        }
        EstimateCard(a, nightsRead)
        CalibrationCard(a) { sys, dia ->
            scope.launch {
                saveCuffReading(vm, sys, dia)
                reload++
            }
        }
        AccuracyCard(a)
        HistoryCard(a)
        Text(uiString(R.string.bp_disclaimer), style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun EstimateCard(a: BloodPressureEstimator.Assessment, nights: List<NightFeatures>) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline(uiString(R.string.bp_estimate_overline))
            val e = a.estimates.firstOrNull()
            if (e == null) {
                Text(uiString(R.string.bp_no_estimate_title), style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    if (nights.isEmpty()) uiString(R.string.bp_no_nights) else uiString(R.string.bp_needs_calibration, BloodPressureEstimator.READINGS_PER_SESSION),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                return@Column
            }
            Text(rangeText(e), style = NoopType.title1, color = Palette.textPrimary)
            Text(uiString(R.string.bp_estimate_night, dayLabel(e.day)), style = NoopType.footnote, color = Palette.textSecondary)
            Text(
                if (e.spreadSys == null) uiString(R.string.bp_spread_unknown, a.comparisons.size, BloodPressureEstimator.MIN_COMPARISONS_FOR_SPREAD)
                else uiString(R.string.bp_spread_known, a.comparisons.size),
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
            val pulse = nights.firstOrNull { it.day == e.day }?.pulse
            Text(
                if (pulse != null) uiString(R.string.bp_pulse_used, pulse.beats) else uiString(R.string.bp_pulse_missing),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun CalibrationCard(a: BloodPressureEstimator.Assessment, onSave: (Double, Double) -> Unit) {
    var sysText by remember { mutableStateOf("") }
    var diaText by remember { mutableStateOf("") }
    val sys = sysText.trim().replace(',', '.').toDoubleOrNull()
    val dia = diaText.trim().replace(',', '.').toDoubleOrNull()
    val valid = sys != null && dia != null && BloodPressureEstimator.plausible(sys, dia)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline(uiString(R.string.bp_calibration_overline))
            Text(uiString(R.string.bp_calibration_how, BloodPressureEstimator.READINGS_PER_SESSION), style = NoopType.footnote, color = Palette.textSecondary)
            val open = a.openSession
            if (open != null) {
                Text(
                    uiString(R.string.bp_session_progress, open.readings.size, BloodPressureEstimator.READINGS_PER_SESSION),
                    style = NoopType.subhead,
                    color = Palette.accent,
                )
            }
            Text(
                when {
                    a.lastCalibration == null -> uiString(R.string.bp_never_calibrated)
                    a.calibrationDue -> uiString(R.string.bp_calibration_due, timeLabel(a.lastCalibration))
                    else -> uiString(R.string.bp_last_calibration, timeLabel(a.lastCalibration), a.completeSessions)
                },
                style = NoopType.footnote,
                color = if (a.calibrationDue) Palette.statusWarning else Palette.textTertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CuffField(sysText, { sysText = it }, uiString(R.string.bp_systolic), Modifier.weight(1f))
                CuffField(diaText, { diaText = it }, uiString(R.string.bp_diastolic), Modifier.weight(1f))
            }
            if (sysText.isNotBlank() && diaText.isNotBlank() && !valid) {
                Text(uiString(R.string.bp_implausible), style = NoopType.footnote, color = Palette.statusWarning)
            }
            NoopButton(text = uiString(R.string.bp_save_reading), fullWidth = true, enabled = valid) {
                if (sys != null && dia != null) {
                    onSave(sys, dia)
                    sysText = ""
                    diaText = ""
                }
            }
        }
    }
}

@Composable
private fun CuffField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        label = { Text(label, style = NoopType.footnote) },
        trailingIcon = { Text("mmHg", style = NoopType.footnote, color = Palette.textTertiary) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.textPrimary,
            unfocusedTextColor = Palette.textPrimary,
            cursorColor = Palette.accent,
            focusedBorderColor = Palette.accent,
            unfocusedBorderColor = Palette.hairline,
            focusedContainerColor = Palette.surfaceInset,
            unfocusedContainerColor = Palette.surfaceInset,
        ),
        modifier = modifier,
    )
}

@Composable
private fun AccuracyCard(a: BloodPressureEstimator.Assessment) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline(uiString(R.string.bp_accuracy_overline))
            if (a.comparisons.isEmpty()) {
                Text(uiString(R.string.bp_accuracy_none), style = NoopType.footnote, color = Palette.textSecondary)
                return@Column
            }
            Text(
                uiString(R.string.bp_accuracy_rmse, (a.rmseSys ?: 0.0).roundToInt(), (a.rmseDia ?: 0.0).roundToInt(), a.comparisons.size),
                style = NoopType.subhead,
                color = Palette.textPrimary,
            )
            Text(uiString(R.string.bp_accuracy_how), style = NoopType.footnote, color = Palette.textTertiary)
            a.comparisons.asReversed().take(8).forEach { c ->
                Text(
                    uiString(
                        R.string.bp_comparison_row,
                        timeLabel(c.sessionStart),
                        "${c.cuffSys.roundToInt()}/${c.cuffDia.roundToInt()}",
                        "${c.estSys.roundToInt()}/${c.estDia.roundToInt()}",
                    ),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(a: BloodPressureEstimator.Assessment) {
    if (a.estimates.size < 2) return
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(uiString(R.string.bp_history_overline))
            Text(uiString(R.string.bp_history_note), style = NoopType.footnote, color = Palette.textTertiary)
            a.estimates.take(14).forEach { e ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(dayLabel(e.day), style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                    Text(rangeText(e), style = NoopType.footnote, color = Palette.textPrimary)
                }
            }
        }
    }
}

private fun rangeText(e: BloodPressureEstimator.Estimate): String {
    val s = e.systolic.roundToInt()
    val d = e.diastolic.roundToInt()
    val ss = e.spreadSys?.roundToInt()
    val sd = e.spreadDia?.roundToInt()
    return if (ss == null || sd == null) "≈ $s / $d mmHg"
    else "${s - ss}–${s + ss} / ${d - sd}–${d + sd} mmHg"
}

private fun dayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
}.getOrDefault(day)

private fun timeLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))

// MARK: - Data

/** Cuff pairs from the Lab Book: a systolic and a diastolic row taken at the same instant. */
private suspend fun loadCuffReadings(vm: AppViewModel): List<CuffReading> = withContext(Dispatchers.IO) {
    val sys = vm.repo.labMarkersByKey(CUFF_DEVICE_ID, LabBookProjection.BP_SYSTOLIC_KEY)
    val dia = vm.repo.labMarkersByKey(CUFF_DEVICE_ID, LabBookProjection.BP_DIASTOLIC_KEY).associateBy { it.takenAt }
    sys.mapNotNull { s ->
        val d = dia[s.takenAt] ?: return@mapNotNull null
        CuffReading(s.takenAt, s.value ?: return@mapNotNull null, d.value ?: return@mapNotNull null)
    }
}

/**
 * One entry per night: the longest sleep that ended on each local day, with that day's resting HR, HRV
 * and respiratory rate, and — while the strap's raw optical waveform is still retained — the pulse shape.
 */
private suspend fun loadNights(vm: AppViewModel, days: List<DailyMetric>): List<NightFeatures> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis() / 1000L
    val zone = ZoneId.systemDefault()
    val active = vm.activeStrapId
    val sessions = runCatching { vm.repo.sleepSessionsMerged(active, now - NIGHTS_BACK_DAYS * 86_400L, now) }.getOrDefault(emptyList())
    val daily = days.associateBy { it.day }
    sessions.groupBy { Instant.ofEpochSecond(it.endTs).atZone(zone).toLocalDate().toString() }
        .mapNotNull { (day, list) -> list.maxByOrNull { it.endTs - it.startTs }?.let { day to it } }
        .map { (day, s) ->
            val dm = daily[day]
            val pulse = if (s.endTs >= now - PULSE_BACK_DAYS * 86_400L) pulseFor(vm, active, s.startTs, s.endTs) else null
            NightFeatures(
                day = day,
                wakeTs = s.endTs,
                restingHr = (dm?.restingHr ?: s.restingHr)?.toDouble(),
                hrvMs = dm?.avgHrv ?: s.avgHrv,
                respRate = dm?.respRateBpm,
                pulse = pulse,
            )
        }
}

private suspend fun pulseFor(vm: AppViewModel, active: String, from: Long, to: Long): BloodPressureEstimator.PulseShape? {
    val rows = listOf(active, CUFF_DEVICE_ID).distinct()
        .flatMap { id -> runCatching { vm.repo.ppgWaveformSamples(id, from, to) }.getOrDefault(emptyList()) }
        .distinctBy { it.ts }
        .sortedBy { it.ts }
    val seconds = rows.mapNotNull { r -> ppgWaveformAbsolute(r.baseCode, r.samples)?.let { r.ts to it } }
    return BloodPressureEstimator.pulseShape(seconds)
}

/** Writes one cuff reading as the Lab Book's systolic/diastolic pair, exactly as its own editor does. */
private suspend fun saveCuffReading(vm: AppViewModel, systolic: Double, diastolic: Double) = withContext(Dispatchers.IO) {
    val epoch = System.currentTimeMillis() / 1000L
    val day = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    fun row(key: String, value: Double) = LabMarkerRow(
        id = "$key-$epoch-${UUID.randomUUID().toString().take(8)}",
        deviceId = CUFF_DEVICE_ID,
        markerKey = key,
        category = LabMarkerCategory.BLOOD_PRESSURE.raw,
        day = day,
        takenAt = epoch,
        value = value,
        valueText = null,
        unit = "mmHg",
        source = "manual",
        note = null,
        referenceText = null,
    )
    vm.repo.upsertLabMarkers(listOf(row(LabBookProjection.BP_SYSTOLIC_KEY, systolic), row(LabBookProjection.BP_DIASTOLIC_KEY, diastolic)))
}
