package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.ble.LiveState
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Today — the home dashboard. A recovery ring hero with a plain-English
 * synthesis, then a tile grid of the day's key metrics (sleep, HRV,
 * resting HR, respiratory rate, SpO2, strain), each with its value +
 * a short caption.
 *
 * Adapted from the Android [TodayScreen]:
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - `stringResource(R.string.*)` → hardcoded strings
 *  - `LocalContext` / `ProfileStore` / `UnitPrefs.system(context)` → [UnitPrefs.system]()
 *  - Android `DatePickerDialog` / `HapticFeedback` → removed
 *  - Liquid-sky background → flat [Palette.surfaceBase]
 */
@Composable
fun TodayScreen(
    viewModel: DesktopAppViewModel,
) {
    val today by viewModel.today.collectAsState()
    val days by viewModel.recentDays.collectAsState()
    val live by viewModel.live.collectAsState()
    val bpm by viewModel.bpm.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val activeDeviceName by viewModel.activeDeviceName.collectAsState()

    val unitSystem = remember { UnitPrefs.system() }
    val tempUnit = remember { UnitPrefs.temperature() }

    val recovery = today?.recovery
    val strain = today?.strain
    val sleepMin = today?.totalSleepMin
    val hrv = today?.avgHrv
    val rhr = today?.restingHr
    val respRate = today?.respRateBpm
    val spo2 = today?.spo2Pct
    val skinTemp = today?.skinTempDevC

    ScreenScaffold(
        title = "Today",
        subtitle = today?.day ?: logicalDayKeyNow(),
        trailing = {
            IconButton(onClick = { viewModel.refreshAll() }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = Palette.textSecondary,
                )
            }
        },
    ) {
        // --- Recovery hero ---
        if (recovery != null) {
            RecoveryHero(
                score = recovery,
                supporting = sleepSummary(sleepMin),
                bpm = bpm,
                live = live,
                batterySoc = live.batteryPct,
                deviceName = activeDeviceName,
            )
        } else {
            DataPendingNote(
                title = "No data yet",
                body = "Connect a WHOOP strap or import history to see your recovery, sleep, and health metrics.",
            )
        }

        // --- Metric tiles ---
        SectionHeader("Today's metrics", overline = "Overview")

        // Row 1: Sleep + HRV
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricTile(
                label = "Sleep",
                value = sleepMin?.let { formatHours(it) } ?: "--",
                caption = sleepMin?.let { "${it} min" } ?: "No data",
                icon = Icons.Filled.Bedtime,
                tint = Palette.restColor,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "HRV",
                value = hrv?.let { "${it.roundToInt()}" } ?: "--",
                caption = "ms",
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                tint = Palette.metricPurple,
                modifier = Modifier.weight(1f),
            )
        }

        // Row 2: Resting HR + Respiratory
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricTile(
                label = "Resting HR",
                value = rhr?.let { "$it" } ?: "--",
                caption = "bpm",
                icon = Icons.Filled.Favorite,
                tint = Palette.metricRose,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Resp. Rate",
                value = respRate?.let { String.format("%.1f", it) } ?: "--",
                caption = "br/min",
                icon = Icons.Filled.MonitorHeart,
                tint = Palette.metricCyan,
                modifier = Modifier.weight(1f),
            )
        }

        // Row 3: SpO2 + Skin Temp
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricTile(
                label = "SpO2",
                value = spo2?.let { "${it.roundToInt()}%" } ?: "--",
                caption = "blood oxygen",
                icon = Icons.Filled.MonitorHeart,
                tint = Palette.metricCyan,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Skin Temp",
                value = skinTemp?.let { UnitFormatter.temperatureDeltaFromCelsius(it, tempUnit) } ?: "--",
                caption = "deviation",
                icon = Icons.Filled.BatteryUnknown,
                tint = Palette.metricAmber,
                modifier = Modifier.weight(1f),
            )
        }

        // --- Strain gauge ---
        if (strain != null) {
            SectionHeader("Day Strain", overline = "Effort")
            NoopCard(padding = 20.dp, tint = Palette.effortColor) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StrainGauge(
                        strain = strain,
                        diameter = 180.dp,
                        modifier = Modifier.padding(8.dp),
                    )
                    Text(
                        text = "Day strain reflects the cardiovascular load accumulated today.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // --- Live connection status ---
        if (live.connected || live.bonded) {
            SectionHeader("Device", overline = "Connection")
            NoopCard(padding = 16.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val tone = if (live.connected) StrandTone.Positive else StrandTone.Warning
                    StatePill(
                        title = if (live.connected) "Connected" else "Bonded",
                        tone = tone,
                        pulsing = live.connected,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            activeDeviceName ?: "WHOOP",
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                        )
                        live.batteryPct?.let { soc ->
                            Text(
                                "Battery ${soc.roundToInt()}%",
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        // --- 14-day recovery trend ---
        if (days.isNotEmpty()) {
            SectionHeader("Recovery trend", overline = "14 days")
            val recoveryValues = days.takeLast(14).mapNotNull { it.recovery }
            if (recoveryValues.isNotEmpty()) {
                NoopCard(padding = 16.dp) {
                    MiniSparkline(
                        values = recoveryValues,
                        color = Palette.accent,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                }
            }
        }

        if (refreshing) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatePill("Refreshing…", tone = StrandTone.Accent, pulsing = true)
            }
        }
    }
}

// MARK: - Recovery hero

@Composable
private fun RecoveryHero(
    score: Double,
    supporting: String?,
    bpm: Int?,
    live: LiveState,
    batterySoc: Double?,
    deviceName: String?,
) {
    NoopCard(padding = 24.dp, tint = Palette.recoveryColor(score)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RecoveryRing(
                score = score,
                diameter = 200.dp,
                supporting = supporting,
            )
            // Live HR + battery row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveHrPill(bpm = bpm, connected = live.connected)
                batterySoc?.let { soc ->
                    BatteryPill(soc = soc, charging = live.charging)
                }
            }
        }
    }
}

@Composable
private fun LiveHrPill(bpm: Int?, connected: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Overline("Live HR", color = Palette.textTertiary)
        Text(
            text = bpm?.let { "$it" } ?: if (connected) "--" else "—",
            style = NoopType.tileValueLarge,
            color = if (bpm != null) Palette.accent else Palette.textTertiary,
        )
        Text("bpm", style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun BatteryPill(soc: Double, charging: Boolean?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Overline("Battery", color = Palette.textTertiary)
        Text(
            text = "${soc.roundToInt()}%",
            style = NoopType.tileValueLarge,
            color = when {
                soc <= 15 -> Palette.statusCritical
                soc <= 30 -> Palette.statusWarning
                else -> Palette.statusPositive
            },
        )
        Text(
            if (charging == true) "charging" else "strap",
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

// MARK: - Metric tile

@Composable
private fun MetricTile(
    label: String,
    value: String,
    caption: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
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
            Text(
                value,
                style = NoopType.tileValue,
                color = tint,
            )
            Text(
                caption,
                style = NoopType.footnote,
                color = Palette.textTertiary,
                maxLines = 1,
            )
        }
    }
}

// MARK: - Mini sparkline

@Composable
private fun MiniSparkline(
    values: List<Double>,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).coerceAtLeast(0.001)
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - ((v - min) / range).toFloat() * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        ))
    }
}

// MARK: - Helpers

private fun formatHours(minutes: Double): String {
    val h = (minutes / 60.0).toInt()
    val m = (minutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun sleepSummary(sleepMin: Double?): String? =
    sleepMin?.let { "${formatHours(it)} slept" }
