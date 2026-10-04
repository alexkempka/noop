package com.noop.ingest

import android.content.Context

/**
 * Which app's body-composition readings NOOP keeps. Android-only addition of this fork (no Swift twin).
 *
 * Two scales on one body disagree: on 25.09.2026 Withings wrote 23.1 % body fat and Fitdays 29.4 % in
 * the same minute, and Google Fit copied the Fitdays value a second time under its own name (Pulse,
 * `docs/WAAGE_UND_HEALTH_CONNECT.md`, section 2b). Each is a different, unpublished estimate, so they are
 * never averaged and never alternated: once the user picks an app, body fat, lean mass, bone mass and
 * body water come only from it. Weight is not filtered — two scales weigh alike (0.3 kg apart there).
 *
 * Nothing is chosen until the user chooses; the app list comes from the records themselves.
 */
object BodyCompositionSource {
    private const val PREFS = "noop_body_composition"
    private const val KEY_CHOSEN = "chosen_package"
    private const val KEY_SEEN = "seen_packages"

    /** True when a record written by [packageName] counts under the user's choice ([chosen] null = all). */
    fun accepts(chosen: String?, packageName: String): Boolean = chosen == null || chosen == packageName

    fun chosen(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHOSEN, null)

    fun setChosen(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (packageName == null) remove(KEY_CHOSEN) else putString(KEY_CHOSEN, packageName)
        }.apply()
    }

    /** Apps that have written body-composition readings NOOP has seen, oldest knowledge kept. */
    fun seen(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SEEN, emptySet()).orEmpty()

    fun recordSeen(context: Context, packages: Set<String>) {
        if (packages.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val merged = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty() + packages
        prefs.edit().putStringSet(KEY_SEEN, merged).apply()
    }

    /**
     * The stored rows to remove after an import under a chosen app: every (day, key) on which some app
     * wrote a reading this import, but the chosen one did not. Such a row can only hold an earlier
     * import's value from another app. Days this import did not see are left alone, so history older
     * than Health Connect lets NOOP read again is never deleted.
     */
    fun staleRows(observed: Set<Pair<String, String>>, accepted: Set<Pair<String, String>>): Set<Pair<String, String>> =
        observed - accepted
}
