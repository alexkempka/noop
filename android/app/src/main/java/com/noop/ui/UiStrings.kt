package com.noop.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.noop.NoopApplication

/**
 * Resource lookup for presentation code that is not itself composable.
 *
 * Most call sites can use Compose's `stringResource`; several NOOP screens deliberately keep pure
 * formatting helpers and data-driven catalogs outside composition. Resolving through the process's
 * Application resources keeps those helpers locale-aware without threading an Activity through the
 * model or storing translated text as an identity/database key.
 */
internal fun uiString(@StringRes id: Int, vararg formatArgs: Any): String =
    NoopApplication.localizedString(id, *formatArgs)

/**
 * Quantity-aware twin of [uiString]. Android picks the right `<item quantity=...>` for [count] using the
 * LOCALE's own plural rules, which is the whole point: hand-rolling `if (n == 1) singular else plural` at
 * the call site bakes in English's TWO categories. That silently under-serves any language with more —
 * Polish has one/few/many/other ("1 noc / 2 noce / 5 nocy"), so no amount of special-casing 1 can spell
 * it correctly. Routed through the Application resources for the same reason as [uiString].
 */
internal fun uiPlural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String =
    NoopApplication.localizedPlural(id, count, *formatArgs)

/**
 * Lookup by English text for copy that stays an English literal in code (catalog entries, helpers whose
 * unit tests pin the English). Resolves `l10n_lit_<sha1>` for the active locale; falls back to the
 * English, formatted with [formatArgs] when given (the template then uses `%1$s`-style placeholders).
 */
/** Display name for a sleep stage. The English names ("Awake", "REM", "Light", "Deep") stay the keys for
 *  colours, shares and row order; only the label on screen is translated. */
internal fun sleepStageLabel(stage: String): String = when (stage) {
    "Awake" -> uiString(com.noop.R.string.sleep_stage_label_awake)
    "Light" -> uiString(com.noop.R.string.sleep_stage_label_light)
    "Deep" -> uiString(com.noop.R.string.sleep_stage_label_deep)
    else -> stage
}

internal fun uiText(english: String, vararg formatArgs: Any?): String =
    NoopApplication.localizedText(english, *formatArgs)

/** A sport name for the screen ("Running" → "Laufen"); the English name stays the stored value. */
internal fun uiSport(english: String): String = NoopApplication.localizedTextIn("sport", english)
