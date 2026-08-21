package com.noop.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * WimHofSettings.kt — the Settings card for Wim Hof breathwork.
 *
 * Everything here is wiring the session screen deliberately does NOT ask about mid-flow: which of the
 * user's own journal items a finished session writes into, where "morning" stops being morning, and
 * the optional background track.
 *
 * The journal pickers list the user's REAL catalog and store the item's CANONICAL key. That matters:
 * renaming a journal item leaves its canonical key untouched (JournalCatalog.kt is explicit about
 * this), so a picked item survives a rename — whereas storing the display name would quietly break the
 * wiring the first time the user tidied their journal.
 */

@Composable
internal fun WimHofSettingsSection(expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current

    var autoJournal by remember { mutableStateOf(WimHofPrefs.autoJournalEnabled(context)) }
    var targets by remember { mutableStateOf(WimHofPrefs.targets(context)) }
    var cutoff by remember { mutableIntStateOf(WimHofPrefs.morningCutoffHour(context)) }
    var trackUri by remember { mutableStateOf(WimHofPrefs.trackUri(context)) }
    var safetyAccepted by remember { mutableStateOf(WimHofPrefs.safetyAccepted(context)) }

    // Resolved catalog = imported + starter + custom, hidden filtered out — exactly what the journal
    // card itself shows, so the picker can't offer an item the user can't see.
    val catalogItems = remember(expanded) {
        resolveJournalItems(
            imported = emptyList(),
            savedItems = loadJournalCatalogItems(context),
            includeHidden = false,
        )
    }

    // First run: pre-select a best guess so the common case needs no configuring, but only ever as the
    // picker's initial selection — a wrong guess is visible and one tap from being corrected, and
    // nothing is written to the journal until the user has seen it here.
    LaunchedEffect(catalogItems) {
        if (targets.morningCanonical == null && targets.eveningCanonical == null) {
            val suggested = suggestWimHofTargets(catalogItems)
            if (suggested.morningCanonical != null || suggested.eveningCanonical != null) {
                WimHofPrefs.setTarget(context, WimHofSlot.MORNING, suggested.morningCanonical)
                WimHofPrefs.setTarget(context, WimHofSlot.EVENING, suggested.eveningCanonical)
                targets = suggested
            }
        }
    }

    val pickTrack = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist the READ grant so the track still plays after a reboot. The file itself is never
        // copied into the app — see WimHofAudio: linking keeps the APK and app storage untouched.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        WimHofPrefs.setTrackUri(context, uri.toString())
        trackUri = uri.toString()
    }

    SettingsSection(
        icon = Icons.Filled.AcUnit,
        title = "Wim Hof breathwork",
        expanded = expanded,
        onToggle = onToggle,
        blurb = "Rounds of power breathing and breath holds, guided on their own screen. A finished session can add its completed rounds straight into your journal.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ToggleRow(
                title = "Log rounds to the journal",
                detail = "When a session ends, add the rounds you completed to your morning or evening breathwork item. Only rounds carried through their recovery hold are counted, and repeat sessions add to the day rather than replacing it.",
                checked = autoJournal,
                onCheckedChange = {
                    autoJournal = it
                    WimHofPrefs.setAutoJournalEnabled(context, it)
                },
            )

            if (autoJournal) {
                RowDivider()
                JournalTargetPicker(
                    label = "Morning item",
                    items = catalogItems,
                    selected = targets.morningCanonical,
                    onSelect = {
                        WimHofPrefs.setTarget(context, WimHofSlot.MORNING, it)
                        targets = targets.copy(morningCanonical = it)
                    },
                )
                JournalTargetPicker(
                    label = "Evening item",
                    items = catalogItems,
                    selected = targets.eveningCanonical,
                    onSelect = {
                        WimHofPrefs.setTarget(context, WimHofSlot.EVENING, it)
                        targets = targets.copy(eveningCanonical = it)
                    },
                )

                RowDivider()
                FormRow(label = "Morning ends at") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            cutoff = (cutoff - 1).coerceAtLeast(MIN_CUTOFF_HOUR)
                            WimHofPrefs.setMorningCutoffHour(context, cutoff)
                        }) { Text("−", style = NoopType.body) }
                        Spacer(Modifier.width(10.dp))
                        Text(String.format(java.util.Locale.US, "%02d:00", cutoff),
                            style = NoopType.number(16f), color = Palette.textPrimary)
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = {
                            cutoff = (cutoff + 1).coerceAtMost(MAX_CUTOFF_HOUR)
                            WimHofPrefs.setMorningCutoffHour(context, cutoff)
                        }) { Text("+", style = NoopType.body) }
                    }
                }
                Text(
                    "Sessions before ${String.format(java.util.Locale.US, "%02d:00", cutoff)} count as morning, from then on as evening.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
                Text(
                    "Rounds are written to the NEXT day's journal row, following Choop's rule that what you do today shapes tomorrow morning's recovery.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }

            RowDivider()

            // Optional ambient track. Linked, never imported — and honest about why it can't be synced.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Background track", style = NoopType.subhead, color = Palette.textPrimary)
                Text(
                    "Optional. Plays as a quiet loop underneath the guide. The guide keeps the timing — a recording can't be synced to a session whose rounds and hold lengths you set yourself. The file stays where it is on your phone; Choop only remembers where to find it, so nothing is added to the app's download size.",
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
                Text(
                    trackUri?.let { "Linked: ${it.substringAfterLast('/')}" } ?: "None linked.",
                    style = NoopType.footnote, color = Palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pickTrack.launch(arrayOf("audio/*")) }) {
                        Text(if (trackUri == null) "Choose a track…" else "Change track…", style = NoopType.body)
                    }
                    if (trackUri != null) {
                        OutlinedButton(onClick = {
                            WimHofPrefs.setTrackUri(context, null)
                            trackUri = null
                        }) { Text("Remove", style = NoopType.body) }
                    }
                }
            }

            RowDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Safety briefing", style = NoopType.subhead, color = Palette.textPrimary)
                Text(
                    if (safetyAccepted) {
                        "Acknowledged. Show it again before your next session."
                    } else {
                        "Not yet acknowledged — it will appear before your next session."
                    },
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
                OutlinedButton(
                    onClick = {
                        WimHofPrefs.clearSafetyAcceptance(context)
                        safetyAccepted = false
                    },
                    enabled = safetyAccepted,
                ) { Text("Show safety briefing again", style = NoopType.body) }
            }

            Text(
                "These settings and your session history live on this phone only — a backup file carries your journal entries, but not this card's setup.",
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

/**
 * A journal item chooser: the current pick, tapping to reveal the list, tapping an entry to set it.
 * A custom list rather than a Material dropdown, matching the app's own chrome (see ThemeGallery /
 * the journal card's own chips). "None" is always offered — the user must be able to unset a target.
 */
@Composable
private fun JournalTargetPicker(
    label: String,
    items: List<JournalCatalogItem>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedItem = items.firstOrNull { normJournalKey(it.canonical) == normJournalKey(selected ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = NoopType.subhead, color = Palette.textPrimary)
                Text(
                    selectedItem?.display ?: selected ?: "Not set — rounds won't be logged",
                    style = NoopType.footnote,
                    color = if (selected == null) Palette.statusWarning else Palette.textSecondary,
                )
            }
            Text(if (open) "▾" else "▸", style = NoopType.caption, color = Palette.textTertiary)
        }

        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PickerRow(text = "None", selected = selected == null) {
                    onSelect(null); open = false
                }
                items.forEach { item ->
                    PickerRow(
                        text = item.display + if (item.kind.isNumeric) "" else "  (yes/no item)",
                        selected = normJournalKey(item.canonical) == normJournalKey(selected ?: ""),
                    ) {
                        onSelect(item.canonical); open = false
                    }
                }
                if (items.isEmpty()) {
                    Text(
                        "No journal items yet. Add one in Insights → journal first.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                } else {
                    // A yes/no item still accepts a number (a numeric write sets answeredYes too), but
                    // the journal card renders it as a toggle, so the count would be invisible there.
                    Text(
                        "Pick a numeric item to see the count in your journal. A yes/no item still records the day, but shows no number.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "●" else "○", style = NoopType.caption,
            color = if (selected) Palette.accent else Palette.textTertiary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = NoopType.body,
            color = if (selected) Palette.textPrimary else Palette.textSecondary)
    }
}
