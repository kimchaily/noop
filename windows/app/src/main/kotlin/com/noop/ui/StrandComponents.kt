package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
internal fun ThreeDaySelectorBar(
    selectedOffset: Int,
    onSelect: (Int) -> Unit,
) {
    val base = LocalDate.now()
    val blockShape = RoundedCornerShape(Metrics.cornerSm)
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.selectorSpacing)) {
        listOf(2, 1, 0).forEach { offset ->
            val day = base.minusDays(offset.toLong())
            val selected = selectedOffset == offset
            val label = when (offset) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> "2 days ago"
            }
            val date = day.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(blockShape)
                    .background(
                        if (selected) Palette.accent.copy(alpha = StrandAlpha.selectedFill)
                        else Palette.surfaceInset,
                    )
                    .border(
                        width = Metrics.divider,
                        color = if (selected) {
                            Palette.accent.copy(alpha = StrandAlpha.selectedBorder)
                        } else {
                            Palette.hairline
                        },
                        shape = blockShape,
                    )
                    .clickable { onSelect(offset) }
                    .padding(vertical = Metrics.selectorPadding, horizontal = Metrics.selectorPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = NoopType.caption,
                    color = if (selected) Palette.textPrimary else Palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = date,
                    style = NoopType.captionNumber,
                    color = if (selected) Palette.accentHover else Palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Metrics.space2),
                )
            }
        }
    }
}

/**
 * Chevron-navigation day selector for the Today screen. The left chevron steps one day
 * older, the right one day newer (disabled at today so a future day can't be selected).
 *
 * Desktop port: the Android DatePickerDialog is replaced with a Compose [AlertDialog]
 * popup that lets the user navigate months and pick a day.
 */
@Composable
internal fun DayNavBar(
    selectedOffset: Int,
    onSelect: (Int) -> Unit,
) {
    val base = LocalDate.now()
    val selectedDay = base.minusDays(selectedOffset.toLong())

    val canGoNewer = selectedOffset > 0
    val label = when (selectedOffset) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> selectedDay.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
    }
    val date = selectedDay.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
    val blockShape = RoundedCornerShape(Metrics.cornerSm)

    var showDatePicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.selectorSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSelect(selectedOffset + 1) }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous day", tint = Palette.accent)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(blockShape)
                .background(Palette.surfaceInset)
                .border(Metrics.divider, Palette.hairline, blockShape)
                .clickable(onClickLabel = "Pick a date") { showDatePicker = true }
                .padding(vertical = Metrics.selectorPadding, horizontal = Metrics.selectorPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = NoopType.caption, color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                date,
                style = NoopType.captionNumber,
                color = Palette.accentHover,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Metrics.space2),
            )
        }
        IconButton(onClick = { if (canGoNewer) onSelect(selectedOffset - 1) }, enabled = canGoNewer) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next day", tint = if (canGoNewer) Palette.accent else Palette.textTertiary)
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            selectedDay = selectedDay,
            today = base,
            onDismiss = { showDatePicker = false },
            onSelect = { day ->
                showDatePicker = false
                val offset = ChronoUnit.DAYS.between(day, base).toInt()
                if (offset >= 0) onSelect(offset)
            },
        )
    }
}

/**
 * A simple date picker popup using AlertDialog. Lets the user navigate months with
 * chevrons and pick a day from the calendar grid.
 */
@Composable
private fun DatePickerDialog(
    selectedDay: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    var viewMonth by remember { mutableStateOf(selectedDay.withDayOfMonth(1)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { viewMonth = viewMonth.minusMonths(1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month", tint = Palette.accent)
                }
                Text(
                    viewMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                    style = NoopType.body,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewMonth = viewMonth.plusMonths(1) }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next month", tint = Palette.accent)
                }
            }
        },
        text = {
            CalendarGrid(
                month = viewMonth,
                selectedDay = selectedDay,
                today = today,
                onSelect = onSelect,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Renders a simple calendar grid for one month. Days before today are selectable;
 * future days are disabled (greyed out).
 */
@Composable
private fun CalendarGrid(
    month: LocalDate,
    selectedDay: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val firstDay = month.withDayOfMonth(1)
    val lastDay = month.withDayOfMonth(month.lengthOfMonth())
    val startOffset = firstDay.dayOfWeek.value % 7 // 0=Sunday
    val days = (0 until (startOffset + month.lengthOfMonth())).toList()
    val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Header row: day-of-week labels
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            dayLabels.forEach { label ->
                Text(
                    label,
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    modifier = Modifier.weight(1f).padding(2.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Day cells
        var dayNum = 1
        for (row in 0..5) {
            if (dayNum > month.lengthOfMonth()) break
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0..6) {
                    val cellIdx = row * 7 + col
                    if (cellIdx < startOffset || dayNum > month.lengthOfMonth()) {
                        Box(modifier = Modifier.weight(1f).height(32.dp))
                    } else {
                        val day = month.withDayOfMonth(dayNum)
                        val isFuture = day.isAfter(today)
                        val isSelected = day == selectedDay
                        val isToday = day == today
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        isSelected -> Palette.accent
                                        isToday -> Palette.accent.copy(alpha = 0.15f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                                .clickable(enabled = !isFuture) { onSelect(day) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$dayNum",
                                style = NoopType.captionNumber,
                                color = when {
                                    isFuture -> Palette.textTertiary
                                    isSelected -> Palette.textPrimary
                                    else -> Palette.textSecondary
                                },
                            )
                        }
                        dayNum++
                    }
                }
            }
        }
    }
}

@Composable
internal fun InsetChartPlaceholder(
    message: String,
    height: Dp = Metrics.compactChartHeight,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = NoopType.subhead, color = Palette.textTertiary)
    }
}

@Composable
internal fun SparkTailBox(
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(start = Metrics.space8, bottom = Metrics.space2)
            .width(if (wide) Metrics.sparkWidthWide else Metrics.sparkWidth)
            .height(Metrics.sparkHeight),
    ) {
        content()
    }
}
