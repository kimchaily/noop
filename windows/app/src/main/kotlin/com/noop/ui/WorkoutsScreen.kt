package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Workouts — the activity log. A range pill (7D / 30D / 90D / All) filters the
 * loaded sessions, a summary grid shows count / total time / calories, and an
 * "All Sessions" card lists each workout row-by-row (date · sport · dur · HR · kcal).
 *
 * Adapted from the Android [WorkoutsScreen]:
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - `LocalContext` / `DatePickerDialog` / `TimePickerDialog` → removed
 *  - Workout editing / merge / dismiss dialogs → removed (read-only desktop port)
 *  - `KeyboardOptions` / `OutlinedTextField` for editing → removed
 *  - `DropdownMenu` overflow → removed
 *  - Source badge kept (Whoop / Manual / Detected)
 */
@Composable
fun WorkoutsScreen(
    viewModel: DesktopAppViewModel,
) {
    val workouts by viewModel.workouts.collectAsState()

    // Range filter
    var rangeDays by remember { mutableStateOf(30) }
    val filtered = remember(workouts, rangeDays) {
        if (rangeDays <= 0) {
            workouts.sortedByDescending { it.startTs }
        } else {
            val cutoff = System.currentTimeMillis() / 1000 - rangeDays * 86_400L
            workouts.filter { it.startTs >= cutoff }.sortedByDescending { it.startTs }
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshWorkouts() }

    LazyScreenScaffold(
        title = "Workouts",
        subtitle = "${filtered.size} sessions",
        trailing = {
            SegmentedPillControl(
                items = listOf(7, 30, 90, 0),
                selection = rangeDays,
                label = { if (it == 0) "All" else "${it}D" },
                onSelect = { rangeDays = it },
            )
        },
    ) {
        if (filtered.isEmpty()) {
            item {
                DataPendingNote(
                    title = "No workouts yet",
                    body = "Recorded activities from your WHOOP strap will appear here.",
                )
            }
            return@LazyScreenScaffold
        }

        // --- Summary tiles ---
        item {
            SectionHeader("Summary", overline = "Selected range")
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                WorkoutSummaryTile("Count", "${filtered.size}", "sessions", Modifier.weight(1f))
                val totalMin = filtered.sumOf { (it.endTs - it.startTs) / 60.0 }
                WorkoutSummaryTile("Time", formatHours(totalMin), "total", Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val totalKcal = filtered.sumOf { it.energyKcal ?: 0.0 }
                WorkoutSummaryTile("Calories", "${totalKcal.roundToInt()}", "kcal", Modifier.weight(1f))
                val avgHr = filtered.mapNotNull { it.avgHr }.average().let { if (it.isNaN()) null else it }
                WorkoutSummaryTile("Avg HR", avgHr?.let { "${it.roundToInt()}" } ?: "--", "bpm", Modifier.weight(1f))
            }
        }

        // --- All sessions ---
        item {
            SectionHeader("All Sessions", overline = "${filtered.size} total")
        }
        item {
            NoopCard(padding = 0.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    filtered.forEachIndexed { i, row ->
                        WorkoutRow(row = row)
                        if (i < filtered.lastIndex) {
                            HorizontalDivider(
                                color = Palette.hairline,
                                modifier = Modifier.padding(start = 50.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Workout summary tile

@Composable
private fun WorkoutSummaryTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = 14.dp, tint = Palette.effortColor) {
        Column {
            Overline(label, color = Palette.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(value, style = NoopType.tileValue, color = Palette.effortColor)
            Text(caption, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - Workout row

@Composable
private fun WorkoutRow(row: WorkoutRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.FitnessCenter,
            contentDescription = null,
            tint = Palette.effortColor,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.sport.replaceFirstChar { it.uppercase() },
                style = NoopType.body.copy(fontWeight = FontWeight.SemiBold),
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatWorkoutDate(row.startTs),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatHours((row.endTs - row.startTs) / 60.0),
                style = NoopType.bodyNumber,
                color = Palette.textPrimary,
            )
            row.energyKcal?.let { kcal ->
                Text(
                    "${kcal.roundToInt()} kcal",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        }
        // Source badge
        val sourceLabel = when {
            row.source.lowercase().endsWith("-noop") -> "Detected"
            row.source.lowercase() == "manual" -> "Manual"
            row.source.lowercase() == "apple-health" -> "Apple"
            row.source.lowercase() == "health-connect" -> "HC"
            else -> "Whoop"
        }
        val sourceTint = when (sourceLabel) {
            "Detected" -> Palette.metricAmber
            "Manual" -> Palette.accent
            else -> Palette.metricCyan
        }
        SourceBadge(text = sourceLabel, tint = sourceTint)
    }
}

// MARK: - Helpers

private fun formatHours(minutes: Double): String {
    val h = (minutes / 60.0).toInt()
    val m = (minutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatWorkoutDate(ts: Long): String {
    val dt = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US))
}
