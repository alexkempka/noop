package com.noop.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.noop.ingest.BodyCompositionSource
import com.noop.ingest.HealthConnectImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * The scale on the Weight screen — Android-only addition of this fork (no Swift twin).
 *
 * Product owner, 04.10.2026: the Weight tile did nothing on tap, and the Withings scale's body
 * composition should show in NOOP. The Withings app writes it to Health Connect; NOOP already read
 * weight, body fat and lean mass from there and now also bone mass and body water. Visceral fat and
 * muscle mass have no Health Connect record type at all, so they cannot arrive this way — the card says
 * so instead of leaving an unexplained gap.
 */

/** The body-composition series a Weight-screen row opens, in display order. */
internal val BODY_COMPOSITION_KEYS = listOf("body_fat", "lean_mass", "bone_mass", "body_water")

/** Every vital-detail key the scale feeds. */
internal val BODY_DETAIL_KEYS: Set<String> = setOf("weight") + BODY_COMPOSITION_KEYS

internal fun bodyMetricTitle(key: String): String = when (key) {
    "body_fat" -> uiText("Body fat")
    "lean_mass" -> uiText("Lean body mass")
    "bone_mass" -> uiText("Bone mass")
    "body_water" -> uiText("Body water")
    else -> key
}

/** The newest reading of each composition key: (day, value), or absent when none is stored. */
internal fun latestByKey(series: Map<String, List<Pair<String, Double>>>): Map<String, Pair<String, Double>> =
    series.mapNotNull { (key, points) ->
        points.filter { it.second.isFinite() && it.second > 0.0 }.maxByOrNull { it.first }?.let { key to it }
    }.toMap()

/** A human name for a Health Connect data origin; the package itself when the phone won't say. */
internal fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

@Composable
internal fun BodyCompositionCard(vm: AppViewModel, onOpenVital: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadTick by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<Map<String, Pair<String, Double>>>(emptyMap()) }
    var chosen by remember { mutableStateOf(BodyCompositionSource.chosen(context)) }
    var seen by remember { mutableStateOf(BodyCompositionSource.seen(context)) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadTick) {
        latest = withContext(Dispatchers.IO) {
            latestByKey(
                BODY_COMPOSITION_KEYS.associateWith { key ->
                    runCatching {
                        vm.repo.resolvedSeries(key, "apple-health", "0000-00-00", "9999-99-99", strapDeviceId = vm.activeStrapId).points
                            .map { it.day to it.value }
                    }.getOrDefault(emptyList())
                },
            )
        }
        seen = BodyCompositionSource.seen(context)
        chosen = BodyCompositionSource.chosen(context)
    }

    fun runImport() {
        importing = true
        status = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm)
                }.isSuccess
            }
            importing = false
            status = if (ok) uiText("Scale values read.") else uiText("Reading from Health Connect failed.")
            reloadTick++
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        val wanted = HealthConnectImporter.permissionsFor(setOf(HealthConnectImporter.ImportCategory.BODY_COMPOSITION))
        if (granted.any { it in wanted }) runImport()
        else status = uiText("Health Connect access not granted.")
    }

    // Turns the body-composition category on, asks for any permission not yet asked (bone mass and body
    // water are new in this build), then reads. The same route the Data Sources screen takes.
    fun readScale() {
        if (HealthConnectImporter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            status = uiText("Health Connect is not available on this phone.")
            return
        }
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            HealthConnectImporter.migrateSelectionFromGrants(context, granted)
            val categories = HealthConnectImporter.selectedCategories(context) +
                HealthConnectImporter.ImportCategory.BODY_COMPOSITION
            HealthConnectImporter.setSelectedCategories(context, categories)
            val wanted = HealthConnectImporter.permissionsFor(categories)
            if (granted.any { it in wanted } && !HealthConnectImporter.hasUnaskedPermissions(context, categories)) {
                runImport()
            } else {
                HealthConnectImporter.markPermissionsAsked(context, categories)
                permissionLauncher.launch(wanted)
            }
        }
    }

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline(uiText("Body composition"))
            for (key in BODY_COMPOSITION_KEYS) {
                val reading = latest[key]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = reading != null) { onOpenVital(key) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(bodyMetricTitle(key), style = NoopType.subhead, color = Palette.textPrimary,
                        modifier = Modifier.weight(1f))
                    if (reading != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                String.format(Locale.getDefault(), "%.1f %s", reading.second,
                                    if (key == "body_fat") "%" else "kg"),
                                style = NoopType.subhead, color = Palette.textPrimary,
                            )
                            Text(vitalReadingDateLabel(reading.first), style = NoopType.footnote,
                                color = Palette.textSecondary)
                        }
                    } else {
                        Text(uiText("No reading"), style = NoopType.footnote, color = Palette.textSecondary)
                    }
                }
            }
            Text(
                uiText("Visceral fat and muscle mass cannot be shown: Health Connect, the store on your phone the scale app writes to, has no place for them."),
                style = NoopType.footnote, color = Palette.textSecondary,
            )
        }
    }

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline(uiText("Scale"))
            Text(
                uiText("Two scales estimate body fat differently, so NOOP takes body composition from one app only. Weight comes from every app."),
                style = NoopType.footnote, color = Palette.textSecondary,
            )
            if (seen.isEmpty()) {
                Text(uiText("No app has delivered body values yet. Read them once, then choose your scale here."),
                    style = NoopType.subhead, color = Palette.textPrimary)
            } else {
                val options: List<String?> = listOf<String?>(null) + seen.sorted()
                for (pkg in options) {
                    val selected = chosen == pkg
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                BodyCompositionSource.setChosen(context, pkg)
                                chosen = pkg
                                // Re-read so the stored values follow the choice at once.
                                readScale()
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) Palette.accent else Palette.textSecondary,
                        )
                        Text(
                            if (pkg == null) uiText("All apps (not recommended with two scales)") else appLabel(context, pkg),
                            style = NoopType.subhead, color = Palette.textPrimary,
                        )
                    }
                }
            }
            NoopButton(
                text = if (importing) uiText("Reading…") else uiText("Read scale values now"),
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                enabled = !importing,
                onClick = { readScale() },
            )
            status?.let { Text(it, style = NoopType.footnote, color = Palette.textSecondary) }
        }
    }
}
