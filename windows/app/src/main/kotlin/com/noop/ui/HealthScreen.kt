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
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.ble.LiveState
import kotlin.math.roundToInt

/**
 * Health — health vitals overview. Live HR hero, then a grid of vital-sign
 * tiles (HRV, SpO2, skin temp, respiratory rate, resting HR) sourced from
 * today's [DailyMetric]. Each tile carries its in-range state colour + caption.
 *
 * Adapted from the Android [HealthScreen]:
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - `LocalContext` / `ProfileStore` → removed (uses default HRmax=190)
 *  - `Lifecycle` / `repeatOnLifecycle` → `DisposableEffect`
 *  - `vital_detail/{key}` navigation → removed (tiles are read-only)
 *  - Lab Book / Fused Record navigation → removed
 */
@Composable
fun HealthScreen(
    viewModel: DesktopAppViewModel,
) {
    val today by viewModel.today.collectAsState()
    val days by viewModel.recentDays.collectAsState()
    val bpm by viewModel.bpm.collectAsState()
    val live by viewModel.live.collectAsState()

    // Health Monitor shows live HR too, so keep the realtime stream on.
    DisposableEffect(Unit) {
        viewModel.requestRealtimeHr()
        onDispose { viewModel.releaseRealtimeHr() }
    }

    val tempUnit = remember { UnitPrefs.temperature() }

    ScreenScaffold(
        title = "Health",
        subtitle = "Vital signs",
    ) {
        // --- Live HR hero ---
        NoopCard(padding = 24.dp, tint = Palette.metricRose) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Overline("Live Heart Rate", color = Palette.textTertiary)
                Text(
                    text = bpm?.let { "$it" } ?: if (live.connected) "--" else "—",
                    style = NoopType.display(64f),
                    color = if (bpm != null) Palette.metricRose else Palette.textTertiary,
                )
                Text("bpm", style = NoopType.footnote, color = Palette.textTertiary)
                if (live.connected) {
                    StatePill("Streaming", tone = StrandTone.Positive, pulsing = true)
                } else if (live.bonded) {
                    StatePill("Bonded", tone = StrandTone.Warning)
                }
            }
        }

        // --- Vital signs grid ---
        SectionHeader("Vital Signs", overline = "Overnight")

        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VitalTile(
                label = "HRV",
                value = today?.avgHrv?.let { "${it.roundToInt()}" } ?: "--",
                caption = "ms",
                icon = Icons.Filled.MonitorHeart,
                tint = Palette.metricPurple,
                modifier = Modifier.weight(1f),
            )
            VitalTile(
                label = "Resting HR",
                value = today?.restingHr?.let { "$it" } ?: "--",
                caption = "bpm",
                icon = Icons.Filled.Favorite,
                tint = Palette.metricRose,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VitalTile(
                label = "SpO2",
                value = today?.spo2Pct?.let { "${it.roundToInt()}%" } ?: "--",
                caption = "blood oxygen",
                icon = Icons.Filled.WaterDrop,
                tint = Palette.metricCyan,
                modifier = Modifier.weight(1f),
            )
            VitalTile(
                label = "Resp. Rate",
                value = today?.respRateBpm?.let { String.format("%.1f", it) } ?: "--",
                caption = "br/min",
                icon = Icons.Filled.Air,
                tint = Palette.metricCyan,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VitalTile(
                label = "Skin Temp",
                value = today?.skinTempDevC?.let {
                    UnitFormatter.temperatureDeltaFromCelsius(it, tempUnit)
                } ?: "--",
                caption = "deviation",
                icon = Icons.Filled.Thermostat,
                tint = Palette.metricAmber,
                modifier = Modifier.weight(1f),
            )
            VitalTile(
                label = "Strain",
                value = today?.strain?.let { String.format("%.1f", it) } ?: "--",
                caption = "day effort",
                icon = Icons.Filled.MonitorHeart,
                tint = Palette.effortColor,
                modifier = Modifier.weight(1f),
            )
        }

        // --- 14-day HRV trend ---
        if (days.isNotEmpty()) {
            SectionHeader("HRV trend", overline = "14 days")
            val hrvValues = days.takeLast(14).mapNotNull { it.avgHrv }
            if (hrvValues.isNotEmpty()) {
                NoopCard(padding = 16.dp, tint = Palette.metricPurple) {
                    MiniTrendLine(
                        values = hrvValues,
                        color = Palette.metricPurple,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                }
            }
        }

        // --- 14-day SpO2 trend ---
        if (days.isNotEmpty()) {
            SectionHeader("SpO2 trend", overline = "14 days")
            val spo2Values = days.takeLast(14).mapNotNull { it.spo2Pct }
            if (spo2Values.isNotEmpty()) {
                NoopCard(padding = 16.dp, tint = Palette.metricCyan) {
                    MiniTrendLine(
                        values = spo2Values,
                        color = Palette.metricCyan,
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                }
            }
        }
    }
}

// MARK: - Vital tile

@Composable
private fun VitalTile(
    label: String,
    value: String,
    caption: String,
    icon: ImageVector,
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
            Text(caption, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        }
    }
}

// MARK: - Mini trend line

@Composable
private fun MiniTrendLine(
    values: List<Double>,
    color: Color,
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
        drawPath(
            path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
