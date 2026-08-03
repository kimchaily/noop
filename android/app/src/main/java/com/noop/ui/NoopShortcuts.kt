package com.noop.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.noop.R

/**
 * Publishes the home-screen launcher shortcuts (long-press the app icon) as DYNAMIC shortcuts.
 *
 * Why dynamic instead of a static `res/xml/shortcuts.xml`: a static shortcut's `<intent>` can only be
 * IMPLICIT (it can't name an owning package, because the file is shared across flavors whose
 * applicationIds differ). With BOTH the stable and preview apps installed side by side, an implicit
 * `noop://` VIEW intent matched *both* of them, so tapping a shortcut popped an Android
 * "Open with Choop / Choop Preview" chooser. A dynamic shortcut instead carries an EXPLICIT
 * `Intent(context, MainActivity::class.java)` — its component is THIS exact app in THIS process — so
 * each app's shortcuts always open that same app. Correct across every flavor (full / demo / preview,
 * plus the `.debug` suffix) with nothing hardcoded, because the component resolves to the running
 * package at build time.
 *
 * The requested destination rides along as the [EXTRA_ROUTE] string extra (dynamic shortcut intents
 * carry extras, unlike a static XML `<intent>`), and MainActivity validates it against [routes] before
 * navigating.
 */
internal object NoopShortcuts {

    /** Intent extra naming the nav route a shortcut wants MainActivity to open. */
    const val EXTRA_ROUTE = "com.noop.shortcut.route"

    /** One shortcut: stable id, the [Destination.route] it opens, its labels and tile icon. Declaration
     *  order is the display order the launcher shows (Live HR · Workout · Journal · Settings) — kept to
     *  four so all fit the ~4 most launchers surface, Settings included. */
    private data class Spec(
        val id: String,
        val route: String,
        val shortLabel: Int,
        val longLabel: Int,
        val icon: Int,
    )

    private val specs = listOf(
        Spec("live", "live", R.string.shortcut_live_short, R.string.shortcut_live_long, R.drawable.ic_shortcut_live),
        Spec("workout", "workouts", R.string.shortcut_workout_short, R.string.shortcut_workout_long, R.drawable.ic_shortcut_workout),
        // The journal lives on the Insights screen, so the "journal" shortcut opens Destination.Insights.
        Spec("journal", "insights", R.string.shortcut_journal_short, R.string.shortcut_journal_long, R.drawable.ic_shortcut_journal),
        Spec("settings", "settings", R.string.shortcut_settings_short, R.string.shortcut_settings_long, R.drawable.ic_shortcut_settings),
    )

    /** The nav routes a shortcut may request — the allow-list MainActivity validates the extra against,
     *  so a crafted intent can't drive navigation to an arbitrary route. */
    val routes: Set<String> = specs.map { it.route }.toSet()

    /** (Re)publish the dynamic shortcuts for this app. Idempotent — safe to call on every launch;
     *  [ShortcutManagerCompat.setDynamicShortcuts] replaces the set wholesale. Wrapped because a shortcut
     *  service hiccup must never take down app start. */
    fun publish(context: Context) {
        val shortcuts = specs.map { s ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_ROUTE, s.route)
            }
            ShortcutInfoCompat.Builder(context, s.id)
                .setShortLabel(context.getString(s.shortLabel))
                .setLongLabel(context.getString(s.longLabel))
                .setIcon(IconCompat.createWithResource(context, s.icon))
                .setIntent(intent)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
