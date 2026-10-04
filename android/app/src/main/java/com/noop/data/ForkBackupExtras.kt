package com.noop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What this fork keeps outside the database, carried in the backup as one extra ZIP entry. Android-only
 * addition of this fork (no Swift twin).
 *
 * The `.noopbak` holds the whole database plus a whitelisted `settings.json`. The fork's own additions live
 * elsewhere — ECG recordings as files, the sleep and step goals, the sleep-mark instants, the chosen scale
 * app and the Today tile choice in preferences — so a restore into a fresh install (the move to the VYRO
 * app, product owner 04.10.2026) would have dropped them. This entry brings them along.
 *
 * Written LAST, after the manifest, so the upstream importers, which read only the entries they know, are
 * unaffected. Only named keys of named preference files are carried, never a whole shared file.
 */
object ForkBackupExtras {
    const val ENTRY_NAME = "vyro-extras.json"
    /** A restore refuses an entry larger than this rather than reading it into memory. */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** Preference file → the keys carried from it; null = every key of a file this fork owns outright. */
    val PREFS: Map<String, Set<String>?> = linkedMapOf(
        "noop_profile" to setOf("sleep_goal_minutes", "sleep_goal_asked", "step_goal"),
        "noop_prefs" to setOf(
            "today.keyMetrics", "today.keyMetricsDetailed", "today.keyMetricsWindowDays",
            "today.keyMetrics.bloodPressureAdded", "today.keyMetricsExpanded",
        ),
        "noop_sleep_marks" to null,
        "noop_body_composition" to null,
    )

    /** ECG recordings live as `ecg-<ms>.json` files under `filesDir/ecg` (EcgScreen.kt). */
    private const val ECG_DIR = "ecg"
    private fun isEcgFile(name: String) = name.startsWith("ecg-") && name.endsWith(".json") && !name.contains('/')

    // ---- Encoding (pure: maps in, JSON out, so the JVM suite covers the round trip) ----

    fun encode(prefs: Map<String, Map<String, Any?>>, ecgFiles: Map<String, String>): String {
        val root = JSONObject().put("version", 1)
        val p = JSONObject()
        for ((file, values) in prefs) {
            val o = JSONObject()
            for ((key, value) in values) {
                val typed = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                } ?: continue
                o.put(key, typed)
            }
            p.put(file, o)
        }
        root.put("prefs", p)
        val e = JSONObject()
        for ((name, text) in ecgFiles) if (isEcgFile(name)) e.put(name, text)
        root.put("ecg", e)
        return root.toString()
    }

    data class Decoded(val prefs: Map<String, Map<String, Any>>, val ecgFiles: Map<String, String>)

    /** Unknown types and file names outside the expected pattern are dropped, never guessed at. */
    fun decode(json: String): Decoded {
        val root = JSONObject(json)
        val prefs = LinkedHashMap<String, Map<String, Any>>()
        val p = root.optJSONObject("prefs") ?: JSONObject()
        for (file in p.keys()) {
            if (file !in PREFS) continue
            val allowed = PREFS[file]
            val o = p.getJSONObject(file)
            val values = LinkedHashMap<String, Any>()
            for (key in o.keys()) {
                if (allowed != null && key !in allowed) continue
                val typed = o.optJSONObject(key) ?: continue
                val v: Any = when (typed.optString("t")) {
                    "b" -> typed.getBoolean("v")
                    "i" -> typed.getInt("v")
                    "l" -> typed.getLong("v")
                    "f" -> typed.getDouble("v").toFloat()
                    "s" -> typed.getString("v")
                    "ss" -> typed.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> continue
                }
                values[key] = v
            }
            prefs[file] = values
        }
        val ecg = LinkedHashMap<String, String>()
        val e = root.optJSONObject("ecg") ?: JSONObject()
        for (name in e.keys()) if (isEcgFile(name)) ecg[name] = e.getString(name)
        return Decoded(prefs, ecg)
    }

    // ---- The device side ----

    fun snapshotJson(context: Context): String {
        val prefs = PREFS.mapValues { (file, keys) ->
            val all = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
            if (keys == null) all else all.filterKeys { it in keys }
        }
        val dir = File(context.filesDir, ECG_DIR)
        val ecg = dir.listFiles { f -> isEcgFile(f.name) }.orEmpty().associate { it.name to it.readText() }
        return encode(prefs, ecg)
    }

    /** Writes the carried values back. ECG files are added, an existing recording of the same name is kept. */
    fun apply(context: Context, json: String) {
        val d = decode(json)
        for ((file, values) in d.prefs) {
            val edit = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            for ((key, v) in values) {
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is Boolean -> edit.putBoolean(key, v)
                    is Int -> edit.putInt(key, v)
                    is Long -> edit.putLong(key, v)
                    is Float -> edit.putFloat(key, v)
                    is String -> edit.putString(key, v)
                    is Set<*> -> edit.putStringSet(key, v as Set<String>)
                }
            }
            edit.commit()
        }
        val dir = File(context.filesDir, ECG_DIR).apply { mkdirs() }
        for ((name, text) in d.ecgFiles) {
            val f = File(dir, name)
            if (!f.exists()) f.writeText(text)
        }
    }
}
