package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Charts (pure Compose Canvas — dark, instrument-grade, no external library)
//
// Implements the shared chart contract used across the port. Every chart is
// null/empty-safe: with no usable data it renders nothing but a faint baseline
// (or, for the hypnogram, the inset well) so layouts never collapse or crash.
//
// Desktop port: android.graphics.Paint replaced with Compose TextMeasurer + drawText.

// MARK: - Accessibility summaries

/** One-line spoken summary of a numeric series: count + latest + low/high. Empty → "No data". */
private fun seriesSummary(values: List<Double>, noun: String): String {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return "$noun, no data"
    val last = clean.last()
    val lo = clean.min()
    val hi = clean.max()
    return "$noun, ${clean.size} points, latest ${formatLineValue(last)}, " +
        "low ${formatLineValue(lo)}, high ${formatLineValue(hi)}"
}

/** Per-stage total summary for the Hypnogram (deep · REM · light · awake, naming only stages present). */
private fun hypnogramSummary(stages: List<Pair<String, Float>>): String {
    if (stages.isEmpty()) return "Sleep stages, no data"
    val total = stages.map { if (it.second.isFinite() && it.second > 0f) it.second else 0f }.sum()
    if (total <= 0f) return "Sleep stages, no data"
    val order = listOf("deep", "rem", "light", "awake")
    val byStage = LinkedHashMap<String, Float>()
    for (key in order) byStage[key] = 0f
    stages.forEach { (name, w) ->
        val v = if (w.isFinite() && w > 0f) w else 0f
        val key = when (name.trim().lowercase()) {
            "deep" -> "deep"; "rem" -> "rem"; "light" -> "light"; "awake", "wake" -> "awake"; else -> "light"
        }
        byStage[key] = (byStage[key] ?: 0f) + v
    }
    val parts = order.mapNotNull { key ->
        val v = byStage[key] ?: 0f
        if (v <= 0f) null else {
            val pct = (v / total * 100f).roundToInt()
            val label = if (key == "rem") "REM" else key.replaceFirstChar { it.uppercase() }
            "$pct percent $label"
        }
    }
    return if (parts.isEmpty()) "Sleep stages, no data" else "Sleep stages, " + parts.joinToString(", ")
}

// MARK: - Shared geometry helpers

private fun pointsFor(
    values: List<Double>,
    width: Float,
    height: Float,
    topPad: Float,
    bottomPad: Float,
): List<Offset> {
    val clean = values.filter { it.isFinite() }
    if (clean.size < 2 || width <= 0f || height <= 0f) return emptyList()
    return pointsFor(clean, width, height, topPad, bottomPad, clean.min(), clean.max())
}

private fun pointsFor(
    values: List<Double>,
    width: Float,
    height: Float,
    topPad: Float,
    bottomPad: Float,
    minV: Double,
    maxV: Double,
): List<Offset> {
    if (values.size < 2 || width <= 0f || height <= 0f) return emptyList()

    val span = (maxV - minV)
    val usableH = (height - topPad - bottomPad).coerceAtLeast(1f)
    val stepX = if (values.size > 1) width / (values.size - 1) else width

    return values.mapIndexed { i, v ->
        val x = stepX * i
        val norm = if (span > 0.0) ((v - minV) / span).toFloat() else 0.5f
        val y = topPad + (1f - norm) * usableH
        Offset(x, y)
    }
}

private fun DrawScope.drawBaseline(color: Color = Palette.hairline) {
    val y = size.height / 2f
    drawLine(
        color = color.copy(alpha = StrandAlpha.subtleLine),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        cap = StrokeCap.Round,
    )
}

// MARK: - Sparkline

@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = Palette.accent,
) {
    val axSummary = seriesSummary(values, "Trend")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Metrics.sparklineHeight)
            .clearAndSetSemantics { contentDescription = axSummary }
            .drawWithCache {
                val strokePx = 2f
                val pad = strokePx
                val pts = pointsFor(values, size.width, size.height, pad, pad)
                if (pts.isEmpty()) {
                    onDrawBehind { drawBaseline() }
                } else {
                    val path = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    }
                    val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    onDrawBehind {
                        drawPath(path = path, color = color, style = stroke)
                    }
                }
            },
    )
}

// MARK: - LineChart

@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier,
    color: Color = Palette.accent,
    fill: Boolean = true,
    selectionEnabled: Boolean = false,
    dragSelectionEnabled: Boolean = true,
    formatValue: ((Double) -> String)? = null,
) {
    val cleanValues = remember(values) { values.filter { it.isFinite() } }
    var selectedIndex by remember(cleanValues) { mutableIntStateOf(-1) }
    val interactiveModifier = if (selectionEnabled) {
        Modifier
            .pointerInput(cleanValues) {
                detectTapGestures(
                    onTap = { offset ->
                        if (cleanValues.size >= 2 && size.width > 0) {
                            selectedIndex = nearestIndexForX(
                                count = cleanValues.size,
                                width = size.width.toFloat(),
                                x = offset.x,
                            )
                        }
                    },
                )
            }
            .then(
                if (dragSelectionEnabled) {
                    Modifier.pointerInput(cleanValues) {
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                if (cleanValues.size < 2 || size.width <= 0f) return@detectHorizontalDragGestures
                                selectedIndex = nearestIndexForX(
                                    count = cleanValues.size,
                                    width = size.width.toFloat(),
                                    x = start.x,
                                )
                            },
                            onHorizontalDrag = { change, _ ->
                                if (cleanValues.size < 2 || size.width <= 0f) return@detectHorizontalDragGestures
                                selectedIndex = nearestIndexForX(
                                    count = cleanValues.size,
                                    width = size.width.toFloat(),
                                    x = change.position.x,
                                )
                                change.consume()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
    } else {
        Modifier
    }

    val axSummary = seriesSummary(cleanValues, "Trend")
    // Desktop: TextMeasurer replaces android.graphics.Paint for the selection label.
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(color) {
        TextStyle(
            color = color.copy(alpha = StrandAlpha.chartLabel),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = axSummary }
            .then(interactiveModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val strokePx = 2.5f
                    val topPad = strokePx + 4f
                    val bottomPad = strokePx + 4f
                    val pts = pointsFor(cleanValues, size.width, size.height, topPad, bottomPad)
                    if (pts.isEmpty()) {
                        onDrawBehind { drawBaseline() }
                    } else {
                        val fillPath = if (fill) {
                            Path().apply {
                                moveTo(pts.first().x, size.height)
                                lineTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                lineTo(pts.last().x, size.height)
                                close()
                            }
                        } else {
                            null
                        }
                        val fillBrush = if (fill) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    color.copy(alpha = StrandAlpha.chartFillStrong),
                                    color.copy(alpha = StrandAlpha.chartFillSoft),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = size.height,
                            )
                        } else {
                            null
                        }
                        val linePath = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        }
                        val lineStroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawBehind {
                            if (fillPath != null && fillBrush != null) {
                                drawPath(path = fillPath, brush = fillBrush)
                            }
                            drawPath(path = linePath, color = color, style = lineStroke)
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (selectionEnabled && selectedIndex >= 0) {
                        val strokePx = 2.5f
                        val topPad = strokePx + 4f
                        val bottomPad = strokePx + 4f
                        val pts = pointsFor(cleanValues, size.width, size.height, topPad, bottomPad)
                        if (selectedIndex in pts.indices) {
                            val p = pts[selectedIndex]
                            drawLine(
                                color = color.copy(alpha = StrandAlpha.chartMarker),
                                start = Offset(p.x, 0f),
                                end = Offset(p.x, size.height),
                                strokeWidth = 1.5f,
                                cap = StrokeCap.Round,
                            )
                            drawCircle(color = color, radius = 5f, center = p)
                            drawCircle(color = Palette.surfaceBase.copy(alpha = StrandAlpha.chartShadow), radius = 9f, center = p)
                            drawCircle(color = color, radius = 4.5f, center = p)
                            // Desktop: drawText with TextMeasurer instead of nativeCanvas.drawText
                            val label = lineChartSelectionLabel(cleanValues[selectedIndex], formatValue)
                            val layout = textMeasurer.measure(label, labelStyle)
                            drawText(layout, topLeft = Offset(8f, 8f))
                        }
                    }
                },
        )
    }
}

data class LineSeries(
    val values: List<Double>,
    val color: Color,
)

@Composable
fun MultiLineChart(
    series: List<LineSeries>,
    modifier: Modifier,
) {
    val cleanSeries = remember(series) {
        series.map { it.copy(values = it.values.filter { value -> value.isFinite() }) }
            .filter { it.values.size >= 2 }
    }

    val axSummary = run {
        val all = cleanSeries.flatMap { it.values }
        if (all.isEmpty()) "Trends, no data"
        else "Trends, ${cleanSeries.size} series, low ${formatLineValue(all.min())}, high ${formatLineValue(all.max())}"
    }

    Canvas(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = axSummary }) {
        if (cleanSeries.isEmpty()) {
            drawBaseline()
            return@Canvas
        }

        val allValues = cleanSeries.flatMap { it.values }
        val minV = allValues.minOrNull() ?: return@Canvas
        val maxV = allValues.maxOrNull() ?: return@Canvas
        val strokePx = 2.5f
        val topPad = strokePx + 4f
        val bottomPad = strokePx + 4f

        cleanSeries.forEach { line ->
            val pts = pointsFor(line.values, size.width, size.height, topPad, bottomPad, minV, maxV)
            if (pts.isEmpty()) return@forEach
            val path = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            drawPath(
                path = path,
                color = line.color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

private fun nearestIndexForX(count: Int, width: Float, x: Float): Int {
    if (count <= 1 || width <= 0f) return 0
    val step = width / (count - 1)
    val clampedX = x.coerceIn(0f, width)
    val raw = (clampedX / step).roundToInt()
    return raw.coerceIn(0, count - 1)
}

internal fun lineChartSelectionLabel(value: Double, formatValue: ((Double) -> String)?): String =
    formatValue?.invoke(value) ?: formatLineValue(value)

private fun formatLineValue(value: Double): String {
    if (!value.isFinite()) return "-"
    val rounded = value.roundToInt().toDouble()
    return if (abs(value - rounded) < 0.05) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

private fun nearestBarIndexForX(count: Int, width: Float, x: Float): Int {
    if (count <= 1 || width <= 0f) return 0
    val slot = width / count
    val clampedX = x.coerceIn(0f, width)
    return (clampedX / slot).toInt().coerceIn(0, count - 1)
}

// MARK: - BarChart

@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier,
    color: Color = Palette.accent,
    selectionEnabled: Boolean = false,
) {
    val cleanValues = remember(values) { values.map { if (it.isFinite() && it > 0.0) it else 0.0 } }
    var selectedIndex by remember(cleanValues) { mutableIntStateOf(-1) }
    // Desktop: TextMeasurer replaces android.graphics.Paint for the bar value label.
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(color) {
        TextStyle(
            color = color.copy(alpha = StrandAlpha.chartLabel),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    val unselectedColor = remember(color) { color.copy(alpha = StrandAlpha.unselectedBar) }

    val axSummary = seriesSummary(cleanValues, "Bars")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = axSummary }
            .then(
                if (selectionEnabled) {
                    Modifier.pointerInput(cleanValues) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (cleanValues.isNotEmpty() && size.width > 0) {
                                    selectedIndex = nearestBarIndexForX(
                                        count = cleanValues.size,
                                        width = size.width.toFloat(),
                                        x = offset.x,
                                    )
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val w = size.width
                val h = size.height
                val maxBars = w.toInt().coerceAtLeast(1)
                val clean = if (!selectionEnabled && cleanValues.size > maxBars && maxBars >= 1) {
                    meanBucketDownsample(cleanValues, maxBars)
                } else {
                    cleanValues
                }
                val maxV = clean.maxOrNull() ?: 0.0
                if (clean.isEmpty() || maxV <= 0.0 || w <= 0f || h <= 0f) {
                    onDrawBehind { drawBaseline() }
                } else {
                    val topPad = 4f
                    val usableH = (h - topPad).coerceAtLeast(1f)
                    val slot = w / clean.size
                    val barWidth = (slot * 0.64f).coerceAtLeast(1f)
                    val capRadius = (barWidth / 2f)
                    data class BarSeg(val cx: Float, val top: Float)
                    val bars = ArrayList<BarSeg>(clean.size)
                    clean.forEachIndexed { i, v ->
                        val norm = (v / maxV).toFloat().coerceIn(0f, 1f)
                        val barHeight = (norm * usableH).coerceAtLeast(if (v > 0.0) 1f else 0f)
                        if (barHeight <= 0f) return@forEachIndexed
                        val cx = slot * i + slot / 2f
                        val top = h - barHeight
                        bars.add(BarSeg(cx, top))
                    }
                    onDrawBehind {
                        bars.forEachIndexed { i, seg ->
                            drawLine(
                                color = if (selectionEnabled && i == selectedIndex) color else unselectedColor,
                                start = Offset(seg.cx, h),
                                end = Offset(seg.cx, (seg.top + capRadius).coerceAtMost(h)),
                                strokeWidth = barWidth,
                                cap = StrokeCap.Round,
                            )
                        }
                        if (selectionEnabled && selectedIndex in clean.indices) {
                            // Desktop: drawText with TextMeasurer instead of nativeCanvas.drawText
                            val label = formatLineValue(clean[selectedIndex])
                            val layout = textMeasurer.measure(label, labelStyle)
                            drawText(layout, topLeft = Offset(8f, 8f))
                        }
                    }
                }
            },
    )
}

private fun meanBucketDownsample(values: List<Double>, target: Int): List<Double> {
    val n = values.size
    if (target < 1 || n <= target) return values
    val out = ArrayList<Double>(target)
    for (b in 0 until target) {
        val lo = (b.toLong() * n / target).toInt()
        val hi = (((b + 1).toLong() * n / target).toInt()).coerceAtMost(n)
        if (hi <= lo) { out.add(values[lo.coerceIn(0, n - 1)]); continue }
        var sum = 0.0
        for (i in lo until hi) sum += values[i]
        out.add(sum / (hi - lo))
    }
    return out
}

// MARK: - Hypnogram

@Composable
fun Hypnogram(
    stages: List<Pair<String, Float>>,
    modifier: Modifier,
) {
    val axSummary = hypnogramSummary(stages)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Metrics.segmentBarHeight)
            .clearAndSetSemantics { contentDescription = axSummary }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val weights = stages.map { it.second }.map { if (it.isFinite() && it > 0f) it else 0f }
                val total = weights.sum()
                if (w <= 0f || h <= 0f || stages.isEmpty() || total <= 0f) {
                    onDrawBehind {
                        if (w > 0f && h > 0f) drawRoundedTrack(Palette.surfaceInset)
                    }
                } else {
                    val segs = ArrayList<Triple<Color, Float, Float>>(stages.size)
                    var x = 0f
                    val gap = if (stages.size > 1) 1.5f else 0f
                    stages.forEachIndexed { i, (name, _) ->
                        val frac = weights[i] / total
                        val segW = (w * frac)
                        if (segW <= 0f) return@forEachIndexed
                        val drawW = (segW - if (i < stages.size - 1) gap else 0f).coerceAtLeast(0f)
                        if (drawW > 0f) segs.add(Triple(stageColor(name), x, drawW))
                        x += segW
                    }
                    onDrawBehind {
                        drawRoundedTrack(Palette.surfaceInset)
                        segs.forEach { (c, left, width) ->
                            drawSegment(color = c, left = left, width = width, height = h)
                        }
                    }
                }
            },
    )
}

// MARK: - SegmentBar

@Composable
fun SegmentBar(
    segments: List<Pair<Color, Float>>,
    modifier: Modifier,
    height: Dp = Metrics.segmentBarHeight,
) {
    val axSummary = if (segments.isEmpty()) "Breakdown, no data" else "Breakdown, ${segments.size} segments"
    Box(modifier = modifier.fillMaxWidth().height(height).clearAndSetSemantics { contentDescription = axSummary }.drawWithCache {
        val w = size.width
        val h = size.height
        val weights = segments.map { it.second }.map { if (it.isFinite() && it > 0f) it else 0f }
        val total = weights.sum()
        if (w <= 0f || h <= 0f || segments.isEmpty() || total <= 0f) {
            onDrawBehind {
                if (w > 0f && h > 0f) drawRoundedTrack(Palette.surfaceInset)
            }
        } else {
            val segs = ArrayList<Triple<Color, Float, Float>>(segments.size)
            var x = 0f
            val gap = if (segments.size > 1) 1.5f else 0f
            segments.forEachIndexed { i, (color, _) ->
                val frac = weights[i] / total
                val segW = w * frac
                if (segW <= 0f) return@forEachIndexed
                val drawW = (segW - if (i < segments.size - 1) gap else 0f).coerceAtLeast(0f)
                if (drawW > 0f) segs.add(Triple(color, x, drawW))
                x += segW
            }
            onDrawBehind {
                drawRoundedTrack(Palette.surfaceInset)
                segs.forEach { (c, left, width) -> drawSegment(color = c, left = left, width = width, height = h) }
            }
        }
    })
}

private fun DrawScope.drawRoundedTrack(color: Color) {
    drawLine(
        color = color,
        start = Offset(0f, size.height / 2f),
        end = Offset(size.width, size.height / 2f),
        strokeWidth = size.height,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSegment(color: Color, left: Float, width: Float, height: Float) {
    val cap = (height / 2f).coerceAtMost(width / 2f)
    drawLine(
        color = color,
        start = Offset(left + cap, height / 2f),
        end = Offset((left + width - cap).coerceAtLeast(left + cap), height / 2f),
        strokeWidth = height,
        cap = StrokeCap.Round,
    )
}

private fun stageColor(name: String): Color = when (name.trim().lowercase()) {
    "deep" -> Palette.sleepDeep
    "rem" -> Palette.sleepREM
    "light" -> Palette.sleepLight
    "awake", "wake" -> Palette.sleepAwake
    else -> Palette.sleepLight
}

// MARK: - Deep Timeline chart

data class TimelinePoint(val ts: Long, val value: Double)

fun timelineBucketSeconds(spanSeconds: Long, targetPoints: Int): Long {
    val span = spanSeconds.coerceAtLeast(1L)
    val target = targetPoints.coerceAtLeast(1)
    val ideal = span / target
    if (ideal <= 1L) return 1L
    val steps = longArrayOf(2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)
    for (s in steps) if (s >= ideal) return s
    return steps.last()
}

fun zoomedWindow(
    base: LongRange,
    scale: Float,
    anchorFraction: Float,
    bounds: LongRange,
    minSpan: Long = 60L,
): LongRange {
    val span = (base.last - base.first).coerceAtLeast(1L)
    if (scale <= 0f) return base
    val pivot = base.first + (span * anchorFraction.coerceIn(0f, 1f)).toLong()
    val boundsSpan = (bounds.last - bounds.first).coerceAtLeast(minSpan)
    val newSpan = (span / scale).toLong().coerceIn(minSpan, boundsSpan)
    var newLo = pivot - ((pivot - base.first).toDouble() * newSpan / span).toLong()
    var newHi = newLo + newSpan
    if (newLo < bounds.first) { newLo = bounds.first; newHi = newLo + newSpan }
    if (newHi > bounds.last) { newHi = bounds.last; newLo = newHi - newSpan }
    newLo = newLo.coerceAtLeast(bounds.first)
    return newLo..(newLo + newSpan).coerceAtLeast(newLo + 1)
}

fun pannedWindow(base: LongRange, deltaSeconds: Long, bounds: LongRange): LongRange {
    val span = base.last - base.first
    var newLo = base.first + deltaSeconds
    newLo = newLo.coerceIn(bounds.first, (bounds.last - span).coerceAtLeast(bounds.first))
    return newLo..(newLo + span)
}

@Composable
fun TimelineChart(
    points: List<TimelinePoint>,
    windowStart: Long,
    windowEnd: Long,
    bounds: LongRange,
    color: Color,
    modifier: Modifier,
    onWindowChange: (LongRange) -> Unit,
) {
    val span = (windowEnd - windowStart).coerceAtLeast(1L)
    val vis = remember(points, windowStart, windowEnd) {
        points.filter { it.ts in windowStart..windowEnd && it.value.isFinite() }
    }

    val axSummary = seriesSummary(vis.map { it.value }, "Timeline")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = axSummary }
            .pointerInput(bounds) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    var window = windowStart..windowEnd
                    if (zoom != 1f) {
                        val frac = (centroid.x / width).coerceIn(0f, 1f)
                        window = zoomedWindow(window, zoom, frac, bounds)
                    }
                    if (pan.x != 0f) {
                        val curSpan = window.last - window.first
                        val secPerPx = curSpan.toDouble() / width
                        window = pannedWindow(window, (-pan.x * secPerPx).toLong(), bounds)
                    }
                    onWindowChange(window)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 2.5f
            val topPad = strokePx + 4f
            val bottomPad = strokePx + 4f
            if (vis.size < 2 || size.width <= 0f || size.height <= 0f) {
                drawBaseline()
                return@Canvas
            }
            val minV = vis.minOf { it.value }
            val maxV = vis.maxOf { it.value }
            val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
            val usable = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            fun px(ts: Long): Float = ((ts - windowStart).toFloat() / span) * size.width
            fun py(v: Double): Float = topPad + ((maxV - v) / range).toFloat() * usable

            val linePath = Path().apply {
                moveTo(px(vis.first().ts), py(vis.first().value))
                for (i in 1 until vis.size) lineTo(px(vis[i].ts), py(vis[i].value))
            }
            val fillPath = Path().apply {
                moveTo(px(vis.first().ts), size.height)
                lineTo(px(vis.first().ts), py(vis.first().value))
                for (i in 1 until vis.size) lineTo(px(vis[i].ts), py(vis[i].value))
                lineTo(px(vis.last().ts), size.height)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = StrandAlpha.chartFillStrong),
                        color.copy(alpha = StrandAlpha.chartFillSoft),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
            )
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
