package com.noop.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.EcgLiveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Route key under vital_detail/, like the blood-pressure screen. */
internal const val ECG_KEY = "ecg"

/** Seconds of curve the live view shows at once. */
private const val LIVE_WINDOW_SEC = 4.0

/**
 * ECG paper runs at 25 mm/s, so one millimetre is 0.04 s and five are 0.2 s. A dp is 1/160 inch, so
 * 25 mm/s is about 157 dp per second; the stored view uses that so a recording reads at paper speed.
 */
private const val PAPER_DP_PER_SEC = 157.5f
private const val SMALL_BOX_SEC = 0.04
private const val BIG_BOX_SEC = 0.2

/**
 * The ECG reading: start, the strap's settling phase, the live curve, a clean close and the stored
 * recordings. Android-only addition of this fork, on top of the session sequence the Test Centre probe
 * proved out (Builds 551/552).
 *
 * Instrumentation, not a medical ECG: no rhythm is named, the strap's classifier byte is stored as a number
 * and never shown as a finding, and the amplitude carries no millivolt scale because none is established.
 */
@Composable
fun EcgScreen(vm: AppViewModel, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val live by vm.ble.ecgLive.collectAsStateWithLifecycle()
    val link by vm.live.collectAsStateWithLifecycle()
    val variant by vm.ble.whoop5VariantFlow.collectAsStateWithLifecycle()
    var startRefused by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var recordings by remember { mutableStateOf<List<EcgRecording>>(emptyList()) }
    var opened by remember { mutableStateOf<EcgRecording?>(null) }

    // Save a completed reading once, keyed on its start so a recomposition never writes it twice.
    val current = live
    LaunchedEffect(current?.startedAtMs, current?.finished) {
        if (current != null && current.finished) {
            saveRecording(context, current, note = null)
            reload++
        }
    }
    LaunchedEffect(reload) { recordings = loadRecordings(context) }

    ScreenScaffold(
        title = uiString(R.string.ecg_title),
        subtitle = uiString(R.string.ecg_subtitle),
        trailing = onClose?.let { close ->
            {
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = uiString(R.string.l10n_today_screen_close_bbfa773e), tint = Palette.textSecondary)
                }
            }
        },
    ) {
        val running = current != null && !current.ended
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Overline(uiString(R.string.ecg_reading_overline))
                Text(statusText(current), style = NoopType.headline, color = Palette.textPrimary)
                Text(hintText(current), style = NoopType.footnote, color = Palette.textSecondary)
                if (current != null && current.samples.isNotEmpty()) {
                    LiveCurve(current)
                    ScaleNote(current.measuredRate)
                }
                if (current != null && (current.liveHr > 0 || current.averageHr > 0)) {
                    Text(
                        uiString(R.string.ecg_heart_rate, if (current.ended && current.averageHr > 0) current.averageHr else current.liveHr),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                    )
                }
                if (startRefused) {
                    Text(uiString(R.string.ecg_start_refused), style = NoopType.footnote, color = Palette.statusWarning)
                }
                if (running) {
                    NoopButton(text = uiString(R.string.ecg_stop), kind = NoopButtonKind.Secondary, fullWidth = true) {
                        vm.ble.ecgLiveFinish()
                    }
                } else {
                    NoopButton(
                        text = uiString(R.string.ecg_start),
                        fullWidth = true,
                        enabled = link.bonded && variant.isMG,
                    ) {
                        startRefused = !vm.ble.ecgLiveStart()
                    }
                    if (!variant.isMG) {
                        Text(uiString(R.string.ecg_needs_mg), style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
        }

        if (current != null && current.finished) NoteCard(context, current) { reload++ }

        if (recordings.isNotEmpty()) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline(uiString(R.string.ecg_recordings_overline))
                    recordings.take(20).forEach { r ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { opened = if (opened?.file == r.file) null else r }) {
                            Text(timeLabel(r.startedAtMs), style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                            Text(
                                if (r.averageHr > 0) uiString(R.string.ecg_heart_rate, r.averageHr) else "—",
                                style = NoopType.footnote,
                                color = Palette.textPrimary,
                            )
                        }
                        if (opened?.file == r.file) StoredCurve(r)
                    }
                }
            }
        }
        Text(uiString(R.string.ecg_disclaimer), style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun statusText(s: EcgLiveState?): String = when (s?.phase) {
    null -> uiString(R.string.ecg_status_ready)
    EcgLiveState.Phase.WAITING_FOR_CONTACT -> uiString(R.string.ecg_status_contact)
    EcgLiveState.Phase.SETTLING -> uiString(R.string.ecg_status_settling)
    EcgLiveState.Phase.MEASURING -> uiString(R.string.ecg_status_measuring, s.progress)
    EcgLiveState.Phase.FINISHED -> uiString(R.string.ecg_status_done)
    EcgLiveState.Phase.INVALID -> uiString(R.string.ecg_status_invalid)
}

@Composable
private fun hintText(s: EcgLiveState?): String = when (s?.phase) {
    null -> uiString(R.string.ecg_hint_ready)
    EcgLiveState.Phase.WAITING_FOR_CONTACT -> uiString(R.string.ecg_hint_contact)
    EcgLiveState.Phase.SETTLING -> {
        val since = s.contactSinceMs?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
        uiString(R.string.ecg_hint_settling, since)
    }
    EcgLiveState.Phase.MEASURING -> uiString(R.string.ecg_hint_measuring)
    EcgLiveState.Phase.FINISHED -> uiString(R.string.ecg_hint_done)
    EcgLiveState.Phase.INVALID -> uiString(R.string.ecg_hint_invalid)
}

@Composable
private fun ScaleNote(rate: Double?) {
    Text(
        if (rate == null) uiString(R.string.ecg_scale_pending)
        else uiString(R.string.ecg_scale_known, rate.roundToInt()),
        style = NoopType.footnote,
        color = Palette.textTertiary,
    )
}

/** The newest [LIVE_WINDOW_SEC] seconds, on the paper grid once the rate is measured. */
@Composable
private fun LiveCurve(s: EcgLiveState) {
    val rate = s.measuredRate
    val window = if (rate != null) (rate * LIVE_WINDOW_SEC).roundToInt() else 500
    val shown = s.samples.takeLast(max(window, 2))
    val range = remember(s.samples.size / 200) { robustRange(s.samples) }
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        val secondsAcross = if (rate != null) LIVE_WINDOW_SEC else null
        drawPaper(secondsAcross?.let { size.width / it.toFloat() })
        drawTrace(shown, range, xStep = size.width / max(window - 1, 1))
    }
}

/** A stored reading at paper speed (≈ 25 mm/s), scrolled sideways. */
@Composable
private fun StoredCurve(r: EcgRecording) {
    val rate = r.measuredRate
    val density = LocalDensity.current
    if (rate == null || r.samples.size < 2) {
        Text(uiString(R.string.ecg_scale_pending), style = NoopType.footnote, color = Palette.textTertiary)
        return
    }
    val seconds = r.samples.size / rate
    val width: Dp = (PAPER_DP_PER_SEC * seconds).dp
    val range = remember(r.file) { robustRange(r.samples) }
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(modifier = Modifier.width(width).height(160.dp)) {
            val pxPerSec = with(density) { PAPER_DP_PER_SEC.dp.toPx() }
            drawPaper(pxPerSec)
            drawTrace(r.samples, range, xStep = (pxPerSec / rate).toFloat())
        }
    }
    Text(uiString(R.string.ecg_paper_speed, r.samples.size, rate.roundToInt()), style = NoopType.footnote, color = Palette.textTertiary)
}

/** 1st–99th percentile of the curve, so one spike does not flatten the rest. Strap units, no volts. */
private fun robustRange(v: List<Int>): Pair<Float, Float> {
    if (v.isEmpty()) return 0f to 1f
    val s = v.sorted()
    val lo = s[(s.size * 0.01).toInt().coerceIn(0, s.size - 1)].toFloat()
    val hi = s[(s.size * 0.99).toInt().coerceIn(0, s.size - 1)].toFloat()
    val pad = max((hi - lo) * 0.15f, 1f)
    return (lo - pad) to (hi + pad)
}

/** ECG-paper grid: thin lines every 0.04 s, strong every 0.2 s; square boxes vertically. */
private fun DrawScope.drawPaper(pxPerSec: Float?) {
    val thin = Palette.hairline.copy(alpha = 0.35f)
    val strong = Palette.metricRose.copy(alpha = 0.35f)
    if (pxPerSec == null) {
        drawLine(thin, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        return
    }
    val small = (pxPerSec * SMALL_BOX_SEC).toFloat()
    val big = (pxPerSec * BIG_BOX_SEC).toFloat()
    if (small >= 3f) {
        var x = 0f
        while (x <= size.width) { drawLine(thin, Offset(x, 0f), Offset(x, size.height)); x += small }
        var y = 0f
        while (y <= size.height) { drawLine(thin, Offset(0f, y), Offset(size.width, y)); y += small }
    }
    var x = 0f
    while (x <= size.width) { drawLine(strong, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f); x += big }
    var y = 0f
    while (y <= size.height) { drawLine(strong, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f); y += big }
}

private fun DrawScope.drawTrace(v: List<Int>, range: Pair<Float, Float>, xStep: Float) {
    if (v.size < 2) return
    val (lo, hi) = range
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    val path = Path()
    v.forEachIndexed { i, s ->
        val x = i * xStep
        val y = size.height - ((s - lo) / span).coerceIn(0f, 1f) * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Palette.textPrimary, style = Stroke(width = 2f))
}

/**
 * After a reading, as the official app asks about symptoms: an optional note in the wearer's own words,
 * stored with the recording. NOOP reads nothing into it.
 */
@Composable
private fun NoteCard(context: Context, s: EcgLiveState, onSaved: () -> Unit) {
    var note by remember(s.startedAtMs) { mutableStateOf("") }
    var saved by remember(s.startedAtMs) { mutableStateOf(false) }
    var doSave by remember { mutableIntStateOf(0) }
    LaunchedEffect(doSave) {
        if (doSave > 0) {
            saveRecording(context, s, note.trim().ifEmpty { null })
            saved = true
            onSaved()
        }
    }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline(uiString(R.string.ecg_note_overline))
            Text(uiString(R.string.ecg_note_hint), style = NoopType.footnote, color = Palette.textSecondary)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(500); saved = false },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.textPrimary,
                    unfocusedTextColor = Palette.textPrimary,
                    cursorColor = Palette.accent,
                    focusedBorderColor = Palette.accent,
                    unfocusedBorderColor = Palette.hairline,
                    focusedContainerColor = Palette.surfaceInset,
                    unfocusedContainerColor = Palette.surfaceInset,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            NoopButton(
                text = uiString(if (saved) R.string.ecg_note_saved else R.string.ecg_note_save),
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                enabled = !saved && note.isNotBlank(),
            ) { doSave++ }
        }
    }
}

private fun timeLabel(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))

// MARK: - Storage: one JSON file per reading in app-private storage. Never leaves the phone.

internal data class EcgRecording(
    val file: String,
    val startedAtMs: Long,
    val samples: List<Int>,
    val measuredRate: Double?,
    val averageHr: Int,
    val note: String?,
)

private fun ecgDir(context: Context) = File(context.filesDir, "ecg").apply { mkdirs() }

private suspend fun saveRecording(context: Context, s: EcgLiveState, note: String?) = withContext(Dispatchers.IO) {
    val file = File(ecgDir(context), "ecg-${s.startedAtMs}.json")
    // The automatic save (note == null) runs again whenever the screen reopens on a finished reading; it
    // must never overwrite a file that may already carry the wearer's note.
    if (note == null && file.exists()) return@withContext
    val o = JSONObject()
        .put("version", 1)
        .put("startedAtMs", s.startedAtMs)
        .put("measuredRate", s.measuredRate ?: JSONObject.NULL)
        .put("averageHr", s.averageHr)
        .put("variabilityRaw", s.variabilityRaw ?: JSONObject.NULL)
        // The strap's own result code, kept as a number. Its meaning is not established; never shown.
        .put("classifierRaw", s.classifierRaw)
        .put("progress", s.progress)
        .put("packets", s.packets)
        .put("note", note ?: JSONObject.NULL)
        .put("samples", JSONArray(s.samples))
    file.writeText(o.toString())
}

private suspend fun loadRecordings(context: Context): List<EcgRecording> = withContext(Dispatchers.IO) {
    ecgDir(context).listFiles { f -> f.name.startsWith("ecg-") && f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val arr = o.getJSONArray("samples")
                EcgRecording(
                    file = f.name,
                    startedAtMs = o.getLong("startedAtMs"),
                    samples = List(arr.length()) { arr.getInt(it) },
                    measuredRate = if (o.isNull("measuredRate")) null else o.getDouble("measuredRate"),
                    averageHr = o.optInt("averageHr", 0),
                    note = if (o.isNull("note")) null else o.optString("note"),
                )
            }.getOrNull()
        }
        .sortedByDescending { it.startedAtMs }
}
