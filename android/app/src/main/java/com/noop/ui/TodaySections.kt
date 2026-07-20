package com.noop.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray

// MARK: - Today section layout (reorder + hide)
//
// The Today screen's top-level sections are user-customisable: order AND visibility. This mirrors the
// existing per-item customisation ([DashboardCardPrefs] / [KeyMetricPrefs]) one level up — the SECTIONS
// themselves, not the items inside them. The fixed chrome (header, day picker) and the situational
// notes/banners/nudges are NOT sections here; they stay auto-managed. The Hero carries its own adjacent
// context cards (effort-zero note, Live-session entry), so hiding/moving the Hero takes them with it.

enum class TodaySection(val raw: String, val title: String) {
    // Default order == the historical Today order, so a fresh install is unchanged.
    HERO("hero", "Score rings (Charge · Effort · Rest)"),
    HEART_RATE("heartRate", "Heart rate"),
    YOUR_CARDS("yourCards", "Your cards"),
    SYNTHESIS("synthesis", "Synthesis"),
    RECOVERY_VITALS("recoveryVitals", "Recovery vitals"),
    KEY_METRICS("keyMetrics", "Key metrics"),
    WORKOUTS("workouts", "Workouts");

    companion object {
        fun fromRaw(raw: String?): TodaySection? = entries.firstOrNull { it.raw == raw }

        /** Canonical order (all sections), used as the default and to list the disabled remainder. */
        val canonicalOrder: List<TodaySection> = entries.toList()

        /** Default = every section enabled, in canonical order (the pre-customisation Today layout). */
        val defaultSelection: List<TodaySection> = canonicalOrder
    }
}

/**
 * Display-only persistence for the Today section layout. Holds an ORDERED list of the ENABLED sections as
 * a JSON array of ids; a section not in the list is hidden. Stored under "today.sections" in the shared
 * [NoopPrefs] store, exactly like [DashboardCardPrefs]. SharedPreferences isn't reactive, so Today reads
 * this into remembered state and refreshes it on resume (the editor lives in Settings → Appearance).
 */
object TodaySectionPrefs {
    private const val KEY = "today.sections"

    /** The enabled sections in display order. An empty/unset value yields the default (all enabled). */
    fun enabled(context: Context): List<TodaySection> =
        decodeEnabled(NoopPrefs.of(context).getString(KEY, null))

    /** Persist the enabled sections in order. Disabled sections are simply omitted. */
    fun setEnabled(context: Context, sections: List<TodaySection>) {
        NoopPrefs.of(context).edit().putString(KEY, encode(sections)).apply()
    }

    fun encode(sections: List<TodaySection>): String {
        val arr = JSONArray()
        sections.forEach { arr.put(it.raw) }
        return arr.toString()
    }

    /**
     * Decode the stored string into an ordered list of enabled sections. Empty/unset yields the default
     * (all enabled). Accepts the JSON-array form (canonical) or a legacy comma-joined form. Unknown ids
     * are dropped, duplicates de-duped; an all-unknown/empty decode falls back to the default so Today is
     * never blanked. Mirrors [DashboardCardPrefs.decodeEnabled].
     */
    fun decodeEnabled(raw: String?): List<TodaySection> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return TodaySection.defaultSelection

        val ids: List<String> = parseJsonArray(trimmed) ?: trimmed.split(",").map { it.trim() }
        val seen = LinkedHashSet<TodaySection>()
        ids.forEach { token -> TodaySection.fromRaw(token)?.let { seen.add(it) } }
        return if (seen.isEmpty()) TodaySection.defaultSelection else seen.toList()
    }

    private fun parseJsonArray(s: String): List<String>? = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrNull()
}

/** One row's working state in the section editor: the section + whether it's currently enabled. */
private data class EditableTodaySection(val section: TodaySection, val enabled: Boolean)

/**
 * The Today-layout editor: toggle each section on/off and reorder with the arrows. Mirrors
 * [DashboardCardsEditorDialog] (same chrome, same arrow-reorder — no drag lib). At least one section must
 * stay enabled (an empty Today reads as a bug). Reached from Settings → Appearance.
 */
@Composable
fun TodaySectionsEditorDialog(
    initial: List<TodaySection>,
    onDismiss: () -> Unit,
    onSave: (List<TodaySection>) -> Unit,
) {
    val items = remember {
        val enabledSet = initial.toHashSet()
        mutableStateListOf<EditableTodaySection>().apply {
            initial.forEach { add(EditableTodaySection(it, true)) }
            TodaySection.canonicalOrder.filter { it !in enabledSet }.forEach { add(EditableTodaySection(it, false)) }
        }
    }

    fun move(from: Int, to: Int) {
        if (from in items.indices && to in items.indices) {
            val item = items.removeAt(from)
            items.add(to, item)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Palette.surfaceOverlay, shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Today layout", style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "Choose which sections show on Today and reorder them with the arrows. " +
                            "The header stays; situational notes and banners are managed automatically.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { items[index] = item.copy(enabled = it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Palette.surfaceBase,
                                    checkedTrackColor = Palette.accent,
                                    uncheckedThumbColor = Palette.textSecondary,
                                    uncheckedTrackColor = Palette.surfaceInset,
                                    uncheckedBorderColor = Palette.hairline,
                                ),
                                modifier = Modifier.semantics { contentDescription = "Show ${item.section.title}" },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.section.title,
                                style = NoopType.body,
                                color = if (item.enabled) Palette.textPrimary else Palette.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { move(index, index - 1) },
                                enabled = index > 0,
                                modifier = Modifier.size(Metrics.iconButton),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Move ${item.section.title} up",
                                    tint = if (index > 0) Palette.textSecondary else Palette.textTertiary,
                                    modifier = Modifier.size(Metrics.iconSmall),
                                )
                            }
                            IconButton(
                                onClick = { move(index, index + 1) },
                                enabled = index < items.lastIndex,
                                modifier = Modifier.size(Metrics.iconButton),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Move ${item.section.title} down",
                                    tint = if (index < items.lastIndex) Palette.textSecondary else Palette.textTertiary,
                                    modifier = Modifier.size(Metrics.iconSmall),
                                )
                            }
                        }
                        if (index < items.lastIndex) {
                            HorizontalDivider(color = Palette.hairline, thickness = 1.dp)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            // Reset to the canonical default: every section enabled, in canonical order.
                            items.clear()
                            TodaySection.defaultSelection.forEach { items.add(EditableTodaySection(it, true)) }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
                    ) { Text("Reset", style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(items.filter { it.enabled }.map { it.section }) },
                        // At least one section must stay visible — an empty Today reads as a bug, not a choice.
                        enabled = items.any { it.enabled },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text("Done", style = NoopType.captionNumber) }
                }
            }
        }
    }
}
