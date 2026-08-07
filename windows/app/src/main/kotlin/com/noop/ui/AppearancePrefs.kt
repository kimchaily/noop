package com.noop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// MARK: - Appearance preferences (System / Light / Dark) + Chart style (Titanium / Classic)
//
// The persisted, live preference surface for the NOOP desktop port. These were originally co-located
// with the pure colour-token data in [PaletteTokens.kt]; they have been extracted here so the
// preference objects ([AppearancePrefs], [ChartStylePrefs]) — which carry Compose snapshot state and
// read/write [NoopPrefs] — have their own home, separate from the inert token data classes
// ([PaletteTokens], [ClassicRamp], DarkTokens, LightTokens, ClassicDark, ClassicLight).
//
// Both objects mirror the Android twins byte-for-byte at the string/persistence level (same pref
// keys, same storage values), only swapping `SharedPreferences` for the JVM-backed [NoopPrefs]. The
// snapshot state (`mutableStateOf`) means a flip re-resolves every `Palette.*` read AND the app
// typeface live, with no flash — [NoopTheme] reads [AppearancePrefs.mode] and [ThemePrefs.family]
// before children compose.

// MARK: - Chart style (data-viz colour mode)

/**
 * The data-viz colour mode. TITANIUM is the modern WHOOP-reset palette (blue-grey canvas, WHOOP
 * red->yellow->green recovery). CLASSIC is the throwback red->green ramp with a purple REM. Only
 * DATA encodings are affected — surfaces/text/accent never change with this toggle.
 */
enum class ChartStyle(val storageValue: String, val label: String) {
    TITANIUM("titanium", "Titanium"),
    CLASSIC("classic", "Classic");

    companion object {
        fun fromStorage(raw: String?): ChartStyle = entries.firstOrNull { it.storageValue == raw } ?: TITANIUM
    }
}

/**
 * Chart-colour preference, persisted via [NoopPrefs] and mirrored in snapshot state so a flip
 * re-colours every gauge/chart live (the [Palette] ramp accessors read [style]).
 *
 * [load] is called once before first composition (no flash); [set] writes + flips.
 */
object ChartStylePrefs {
    private const val KEY = "chart.style"

    /** Live chart-colour mode read by [Palette]; defaults to Titanium until [load] runs. */
    var style by mutableStateOf(ChartStyle.TITANIUM)
        private set

    fun load() {
        style = ChartStyle.fromStorage(NoopPrefs.getString(KEY, ChartStyle.TITANIUM.storageValue))
    }

    fun set(value: ChartStyle) {
        style = value
        NoopPrefs.edit().putString(KEY, value.storageValue).apply()
    }
}

// MARK: - Appearance mode (System / Light / Dark)

/**
 * The light/dark appearance mode. SYSTEM defers to the platform's dark-mode setting (on the JVM,
 * [NoopTheme] resolves this via `isSystemInDarkTheme()`). LIGHT and DARK force the respective
 * variant of the selected [AppTheme] family.
 */
enum class AppearanceMode(val storageValue: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorage(raw: String?): AppearanceMode =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

/**
 * Appearance preference, persisted via [NoopPrefs] and mirrored in snapshot state so the toggle is
 * live. [NoopTheme] reads [mode] before children compose, so a flip re-resolves every `Palette.*`
 * read with no flash. [load] is called once before first composition; [set] writes + flips.
 */
object AppearancePrefs {
    private const val KEY = "theme.appearance"

    /** Live appearance mode read by [NoopTheme]; defaults to System until [load] runs. */
    var mode by mutableStateOf(AppearanceMode.SYSTEM)
        private set

    fun load() {
        mode = AppearanceMode.fromStorage(NoopPrefs.getString(KEY, AppearanceMode.SYSTEM.storageValue))
    }

    fun set(value: AppearanceMode) {
        mode = value
        NoopPrefs.edit().putString(KEY, value.storageValue).apply()
    }
}
