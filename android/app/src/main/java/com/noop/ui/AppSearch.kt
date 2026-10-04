package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/*
 * Search on the More page — Android-only addition of this fork (product owner 04.10.2026: "Wir müssen eine
 * Suche einbauen, sind echt viele Settings"). Finds every More destination by its title, plus the places
 * people look for by task ("Blutdruck kalibrieren", "Schlafziel") that live inside a screen. Each hit
 * navigates to the screen that holds it; a setting inside the long Settings page opens that page.
 */

/** One searchable place: what it is called, extra words people search for, and where it opens. */
internal data class SearchEntry(val title: String, val keywords: List<String>, val route: String)

internal object AppSearch {
    /** Case- and accent-insensitive "contains" over the title and the keywords; every word must match. */
    fun matches(entry: SearchEntry, query: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val hay = normalize((listOf(entry.title) + entry.keywords).joinToString(" "))
        return words.all { hay.contains(it) }
    }

    fun search(entries: List<SearchEntry>, query: String): List<SearchEntry> =
        entries.filter { matches(it, query) }.distinctBy { it.title to it.route }

    internal fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ß", "ss")
}

/** The task entries that live inside a screen (kept next to the routes they open). */
internal fun taskSearchEntries(): List<SearchEntry> {
    val settings = Destination.Settings.route
    return listOf(
        SearchEntry(uiText("Blood pressure — estimate and cuff calibration"), listOf("Blutdruck", "kalibrieren", "Kalibrierung", "Manschette", "blood pressure", "calibrate"), "vital_detail/$BLOOD_PRESSURE_KEY"),
        SearchEntry(uiText("ECG reading"), listOf("EKG", "ECG", "Herz", "Messung"), "vital_detail/$ECG_KEY"),
        SearchEntry(uiText("Step goal"), listOf("Schritte", "Ziel", "steps", "goal"), "vital_detail/$STEP_GOAL_KEY"),
        SearchEntry(uiText("Sleep goal"), listOf("Schlaf", "Schlafbedarf", "Ziel", "sleep need"), settings),
        SearchEntry(uiText("Step calibration"), listOf("Schritte", "Kalibrierung", "Teiler", "steps"), settings),
        SearchEntry(uiText("Profile: age, weight, height, max heart rate"), listOf("Profil", "Alter", "Gewicht", "Größe", "Maximalpuls", "Pulszonen", "Taille"), settings),
        SearchEntry(uiText("Appearance: background, transparent cards, motion"), listOf("Erscheinungsbild", "Hintergrund", "Karten", "Transparenz", "Bewegung", "Ringe", "dunkel", "hell", "Farbe"), settings),
        SearchEntry(uiText("Share strap log"), listOf("Band-Protokoll", "Protokoll", "Log", "teilen", "Fehler"), settings),
        SearchEntry(uiText("Units"), listOf("Einheiten", "kg", "Temperatur", "metrisch"), settings),
    )
}

/** All More destinations as entries, titled the way the More page titles them. */
@Composable
internal fun destinationSearchEntries(): List<SearchEntry> =
    drawerGroups.flatMap { it.items }.map { dest ->
        SearchEntry(stringResource(dest.titleRes), listOf(dest.route.replace('_', ' ')), dest.route)
    }

@Composable
internal fun MoreSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.textTertiary) },
        placeholder = { Text(uiText("Search NOOP — e.g. blood pressure, sleep goal"), style = NoopType.subhead, color = Palette.textTertiary) },
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
}

@Composable
internal fun MoreSearchResults(results: List<SearchEntry>, onOpen: (String) -> Unit) {
    NoopCard(padding = 0.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (results.isEmpty()) {
                Text(
                    uiText("Nothing found. Try another word."),
                    style = NoopType.subhead, color = Palette.textSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            results.forEachIndexed { i, r ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(r.route) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(r.title, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(Metrics.iconSmall))
                }
                if (i < results.lastIndex) HorizontalDivider(color = Palette.hairline, modifier = Modifier.padding(start = 48.dp))
            }
        }
    }
}
