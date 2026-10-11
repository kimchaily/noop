package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.ble.LiveState
import kotlin.math.roundToInt

/**
 * Live — the real-time strap view. A big smoothed HR number, a connection
 * pill, a battery/last-event status grid, and connect/disconnect controls.
 *
 * Adapted from the Android [LiveScreen]:
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - `LocalContext` / `UnitPrefs.system(context)` → [UnitPrefs.system]()
 *  - BLE permission flow → removed (desktop has no runtime BLE permission gate)
 *  - Liquid-sky background → flat [Palette.surfaceBase]
 *  - Toggles the realtime HR stream on/off as the screen enters/leaves composition
 */
@Composable
fun LiveScreen(
    viewModel: DesktopAppViewModel,
) {
    val live by viewModel.live.collectAsState()
    val bpm by viewModel.bpm.collectAsState()
    val activeDeviceName by viewModel.activeDeviceName.collectAsState()

    // Keep the realtime HR stream on while this screen is visible — but only after
    // the strap is actually connected. Requesting HR before the bridge is running
    // would increment the ref-count without sending the command, causing a stale
    // decrement (and wrongly disabling HR) on dispose.
    DisposableEffect(live.connected) {
        if (live.connected) {
            viewModel.requestRealtimeHr()
        }
        onDispose {
            viewModel.releaseRealtimeHr()
        }
    }
    // Refresh battery on bond.
    LaunchedEffect(live.bonded) {
        if (live.bonded) viewModel.getBattery()
    }

    val activeConnection = live.connected && live.bonded
    val isConnecting = live.scanning || (live.connected && !live.bonded)

    ScreenScaffold(
        title = "Live",
        subtitle = activeDeviceName ?: "WHOOP",
        trailing = {
            IconButton(onClick = { viewModel.getBattery() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Palette.textSecondary)
            }
        },
    ) {
        // --- HR hero ---
        NoopCard(padding = 28.dp, tint = Palette.accent) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Overline("Heart Rate", color = Palette.textTertiary)
                Text(
                    text = bpm?.let { "$it" } ?: if (activeConnection) "--" else "—",
                    style = NoopType.display(88f),
                    color = if (bpm != null) Palette.accent else Palette.textTertiary,
                )
                Text("bpm", style = NoopType.footnote, color = Palette.textTertiary)

                // Connection pill — distinguish "connected but bonding" from "fully connected"
                val tone = when {
                    activeConnection -> StrandTone.Positive
                    isConnecting -> StrandTone.Warning
                    else -> StrandTone.Critical
                }
                val connLabel = when {
                    activeConnection -> "Connected"
                    live.connected && !live.bonded -> "Bonding…"
                    live.scanning -> "Scanning…"
                    live.bonded -> "Bonded — not streaming"
                    else -> "Disconnected"
                }
                StatePill(connLabel, tone = tone, pulsing = live.connected)
            }
        }

        // --- Status note (shows bridge log, scan progress, errors) ---
        live.statusNote?.let { note ->
            SectionHeader("Status", overline = "BLE Bridge")
            NoopCard(
                padding = 14.dp,
                tint = if (note.startsWith("Error") || note.startsWith("[bridge]")) Palette.metricRose
                       else if (note.startsWith("Found") || note.startsWith("Connected")) Palette.chargeColor
                       else Palette.accent,
            ) {
                Text(
                    note,
                    style = NoopType.body,
                    color = Palette.textPrimary,
                )
            }
        }

        // --- R-R intervals ---
        if (live.rr.isNotEmpty()) {
            SectionHeader("R-R Intervals", overline = "Latest")
            NoopCard(padding = 16.dp, tint = Palette.metricCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    live.rr.takeLast(10).forEach { rr ->
                        Text(
                            "${rr}ms",
                            style = NoopType.mono,
                            color = Palette.metricCyan,
                        )
                    }
                }
            }
        }

        // --- Status grid ---
        SectionHeader("Device Status", overline = "Hardware")
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatusTile(
                label = "Battery",
                value = live.batteryPct?.let { "${it.roundToInt()}%" } ?: "--",
                icon = Icons.Filled.Bolt,
                tint = Palette.chargeColor,
                modifier = Modifier.weight(1f),
            )
            StatusTile(
                label = "Connection",
                value = if (live.connected) "Live" else if (live.bonded) "Bonded" else "Off",
                icon = Icons.Filled.Bluetooth,
                tint = Palette.accent,
                modifier = Modifier.weight(1f),
            )
        }

        // --- Controls ---
        SectionHeader("Controls", overline = "Strap")
        NoopCard(padding = 16.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (activeConnection) {
                    NoopButton(
                        text = "Disconnect",
                        kind = NoopButtonKind.Secondary,
                        leadingIcon = Icons.Filled.Bluetooth,
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (isConnecting) {
                    NoopButton(
                        text = live.statusNote ?: "Connecting…",
                        kind = NoopButtonKind.Secondary,
                        leadingIcon = Icons.Filled.Bluetooth,
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    NoopButton(
                        text = "Connect to strap",
                        kind = NoopButtonKind.Primary,
                        leadingIcon = Icons.Filled.Bluetooth,
                        onClick = { viewModel.connect() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                NoopButton(
                    text = "Refresh battery",
                    kind = NoopButtonKind.Tertiary,
                    leadingIcon = Icons.Filled.Refresh,
                    onClick = { viewModel.getBattery() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // --- Last event ---
        live.lastEvent?.let { event ->
            SectionHeader("Last Event", overline = "Strap")
            NoopCard(padding = 16.dp, tint = Palette.metricAmber) {
                Text(
                    event,
                    style = NoopType.body,
                    color = Palette.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun StatusTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = 14.dp, tint = tint) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Overline(label, color = Palette.textTertiary)
            }
            Spacer(Modifier.weight(1f))
            Text(value, style = NoopType.tileValue, color = tint)
        }
    }
}
