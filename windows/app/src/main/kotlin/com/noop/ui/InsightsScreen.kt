package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.sqrt

// MARK: - Insights — behaviour effect sizes (Cohen's d) + metric relationships (Pearson r)
//
// Desktop port of the Android InsightsScreen. Derives "behaviours" from the cached
// DailyMetric fields rather than from JournalEntry (the Windows port has no journal
// data class). Two sections:
//   1. Behavior Effects — ranked Cohen's d effect cards for derived splits
//   2. Metric Relationships — curated Pearson correlations as one-line readings
//
// Desktop differences from Android:
//   - collectAsState() instead of collectAsStateWithLifecycle()
//   - No liquid UI — plain NoopCard surfaces
//   - No Android Context — uses viewModel properties directly
//   - Behaviours derived from DailyMetric splits (steps, sleep, strain)

// MARK: - Outcome definition

enum class Outcome(val label: String, val higherIsBetter: Boolean, val pick: (DailyMetric) -> Double?) {
    Charge("Charge", higherIsBetter = true,  pick = { it.recovery }),
    HRV("HRV",       higherIsBetter = true,  pick = { it.avgHrv }),
    Rest("Rest",     higherIsBetter = false, pick = { it.restingHr?.toDouble() }),
    Sleep("Sleep",   higherIsBetter = true,  pick = { it.totalSleepMin });
}

// MARK: - Result data classes

data class BehaviorEffect(
    val label: String,
    val withMean: Double,
    val withoutMean: Double,
    val withCount: Int,
    val withoutCount: Int,
    val cohensD: Double,
    val isSignificant: Boolean,
    val magnitude: String,
)

data class MetricCorrelation(
    val label: String,
    val r: Double,
    val n: Int,
    val reading: String,
)

// MARK: - Screen

@Composable
fun InsightsScreen(viewModel: DesktopAppViewModel) {
    val days by viewModel.recentDays.collectAsState()
    val ordered = remember(days) { days.reversed() }

    var outcome by remember { mutableStateOf(Outcome.Charge) }

    val behaviorEffects = remember(ordered, outcome) {
        computeBehaviorEffects(ordered, outcome)
    }

    val correlations = remember(ordered) {
        computeMetricCorrelations(ordered)
    }

    LazyScreenScaffold(
        title = "Insights",
        subtitle = "What affects what — behaviour effect sizes and metric relationships.",
    ) {
        // ---- Outcome selector ----
        item {
            SegmentedPillControl(
                items = Outcome.entries.toList(),
                selection = outcome,
                label = { it.label },
                onSelect = { outcome = it },
            )
        }

        // ---- Behavior Effects ----
        item {
            SectionHeader(
                title = "Behavior Effects",
                overline = "Cohen's d",
                trailing = if (behaviorEffects.isNotEmpty()) "${behaviorEffects.size} found" else null,
            )
        }

        if (behaviorEffects.isEmpty()) {
            item {
                NoopCard(padding = 18.dp) {
                    Text(
                        "Not enough data to compare behaviour effects. Sync more days to " +
                            "populate steps, sleep and strain across multiple metrics.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }
        } else {
            items(behaviorEffects.size) { i ->
                EffectCard(behaviorEffects[i], outcome)
            }
        }

        // ---- Metric Relationships ----
        item {
            Spacer(Modifier.height(Metrics.sectionGap - Metrics.screenRowSpacing))
        }

        item {
            SectionHeader(
                title = "Metric Relationships",
                overline = "Pearson r",
                trailing = if (correlations.isNotEmpty()) "${correlations.size} found" else null,
            )
        }

        if (correlations.isEmpty()) {
            item {
                NoopCard(padding = 18.dp) {
                    Text(
                        "Not enough overlapping data to compute correlations between metrics.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }
        } else {
            items(correlations.size) { i ->
                CorrelationCard(correlations[i])
            }
        }
    }
}

// MARK: - Effect card

@Composable
private fun EffectCard(eff: BehaviorEffect, outcome: Outcome) {
    val effectSign = if (outcome.higherIsBetter) eff.cohensD > 0 else eff.cohensD < 0
    val statusTone = if (eff.isSignificant && effectSign) StrandTone.Positive
        else if (eff.isSignificant) StrandTone.Warning
        else StrandTone.Neutral
    val tint = statusTone.color

    NoopCard(padding = 18.dp, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    eff.label,
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                StatePill(
                    title = if (eff.isSignificant) "significant" else "not significant",
                    tone = statusTone,
                    showsDot = false,
                )
                Spacer(Modifier.padding(start = 8.dp))
                StatePill(
                    title = eff.magnitude,
                    tone = StrandTone.Accent,
                    showsDot = false,
                )
            }

            // d value
            Text(
                "d = ${formatDecimal(eff.cohensD)}",
                style = NoopType.number(18f),
                color = tint,
            )

            // Group means
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("With", style = NoopType.footnote, color = Palette.textTertiary)
                    Text(
                        formatDecimal(eff.withMean),
                        style = NoopType.captionNumber,
                        color = Palette.textPrimary,
                    )
                    Text(
                        "n = ${eff.withCount}",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Without", style = NoopType.footnote, color = Palette.textTertiary)
                    Text(
                        formatDecimal(eff.withoutMean),
                        style = NoopType.captionNumber,
                        color = Palette.textPrimary,
                    )
                    Text(
                        "n = ${eff.withoutCount}",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

// MARK: - Correlation card

@Composable
private fun CorrelationCard(c: MetricCorrelation) {
    val absR = abs(c.r)
    val tone = when {
        absR < 0.1 -> StrandTone.Neutral
        absR < 0.3 -> StrandTone.Neutral
        absR < 0.5 -> StrandTone.Accent
        else -> StrandTone.Positive
    }

    NoopCard(padding = 16.dp, tint = tone.color) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(c.label, style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    c.reading,
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "n = ${c.n}",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "r = ${formatDecimal(c.r)}",
                style = NoopType.number(16f),
                color = tone.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - Behavior effect computation

private fun computeBehaviorEffects(days: List<DailyMetric>, outcome: Outcome): List<BehaviorEffect> {
    val effects = mutableListOf<BehaviorEffect>()

    // Steps > 8000
    computeSplitEffect(
        days, outcome,
        label = "Steps > 8k",
        hasBehavior = { it.steps != null && it.steps!! > 8000 },
        lacksBehavior = { it.steps != null && it.steps!! <= 8000 },
    )?.let { effects.add(it) }

    // Sleep > 7h (420 min)
    computeSplitEffect(
        days, outcome,
        label = "Sleep > 7h",
        hasBehavior = { it.totalSleepMin != null && it.totalSleepMin!! > 420.0 },
        lacksBehavior = { it.totalSleepMin != null && it.totalSleepMin!! <= 420.0 },
    )?.let { effects.add(it) }

    // Strain > 10
    computeSplitEffect(
        days, outcome,
        label = "Strain > 10",
        hasBehavior = { it.strain != null && it.strain!! > 10.0 },
        lacksBehavior = { it.strain != null && it.strain!! <= 10.0 },
    )?.let { effects.add(it) }

    return effects.sortedByDescending { abs(it.cohensD) }
}

private fun computeSplitEffect(
    days: List<DailyMetric>,
    outcome: Outcome,
    label: String,
    hasBehavior: (DailyMetric) -> Boolean,
    lacksBehavior: (DailyMetric) -> Boolean,
): BehaviorEffect? {
    val withOutcome = mutableListOf<Double>()
    val withoutOutcome = mutableListOf<Double>()

    for (d in days) {
        val v = outcome.pick(d) ?: continue
        if (hasBehavior(d)) {
            withOutcome.add(v)
        } else if (lacksBehavior(d)) {
            withoutOutcome.add(v)
        }
    }

    val n1 = withOutcome.size
    val n2 = withoutOutcome.size
    if (n1 < 3 || n2 < 3) return null

    val mean1 = withOutcome.sum() / n1
    val mean2 = withoutOutcome.sum() / n2

    val var1 = withOutcome.sumOf { (it - mean1) * (it - mean1) } / (n1 - 1)
    val var2 = withoutOutcome.sumOf { (it - mean2) * (it - mean2) } / (n2 - 1)

    val pooledSD = sqrt(((n1 - 1.0) * var1 + (n2 - 1.0) * var2) / (n1 + n2 - 2.0))
    val d = if (pooledSD > 0.0) (mean1 - mean2) / pooledSD else 0.0

    val absD = abs(d)
    val magnitude = when {
        absD < 0.2 -> "negligible"
        absD < 0.5 -> "small"
        absD < 0.8 -> "medium"
        else -> "large"
    }

    return BehaviorEffect(
        label = label,
        withMean = mean1,
        withoutMean = mean2,
        withCount = n1,
        withoutCount = n2,
        cohensD = d,
        isSignificant = absD > 0.2,
        magnitude = magnitude,
    )
}

// MARK: - Metric correlation computation

private fun computeMetricCorrelations(days: List<DailyMetric>): List<MetricCorrelation> {
    val result = mutableListOf<MetricCorrelation>()

    // HRV vs Charge
    pearsonR("HRV \u2194 Charge", days.mapNotNull { d ->
        val h = d.avgHrv ?: return@mapNotNull null
        val r = d.recovery ?: return@mapNotNull null
        h to r
    })?.let { result.add(it) }

    // Sleep vs Charge
    pearsonR("Sleep \u2194 Charge", days.mapNotNull { d ->
        val s = d.totalSleepMin ?: return@mapNotNull null
        val r = d.recovery ?: return@mapNotNull null
        s to r
    })?.let { result.add(it) }

    // RHR vs Charge
    pearsonR("RHR \u2194 Charge", days.mapNotNull { d ->
        val h = d.restingHr ?: return@mapNotNull null
        val r = d.recovery ?: return@mapNotNull null
        h.toDouble() to r
    })?.let { result.add(it) }

    // Charge lag-1 autocorrelation
    val chargeSeries = days.mapNotNull { it.recovery }
    if (chargeSeries.size >= 4) {
        val today = chargeSeries.dropLast(1)
        val tomorrow = chargeSeries.drop(1)
        val pairs = today.zip(tomorrow)
        pearsonR("Charge \u2192 next-day Charge", pairs)?.let { result.add(it) }
    }

    return result
}

private fun pearsonR(label: String, pairs: List<Pair<Double, Double>>): MetricCorrelation? {
    val n = pairs.size
    if (n < 3) return null

    val xs = pairs.map { it.first }
    val ys = pairs.map { it.second }

    val meanX = xs.sum() / n
    val meanY = ys.sum() / n

    val num = pairs.sumOf { (it.first - meanX) * (it.second - meanY) }
    val denX = sqrt(xs.sumOf { (it - meanX) * (it - meanX) })
    val denY = sqrt(ys.sumOf { (it - meanY) * (it - meanY) })

    val r = if (denX > 0.0 && denY > 0.0) num / (denX * denY) else 0.0

    val absR = abs(r)
    val reading = when {
        absR < 0.1 -> "no meaningful relationship"
        absR < 0.3 -> "weak ${if (r >= 0) "positive" else "negative"} relationship"
        absR < 0.5 -> "moderate ${if (r >= 0) "positive" else "negative"} relationship"
        absR < 0.7 -> "strong ${if (r >= 0) "positive" else "negative"} relationship"
        else -> "very strong ${if (r >= 0) "positive" else "negative"} relationship"
    }

    return MetricCorrelation(label = label, r = r, n = n, reading = reading)
}

// MARK: - Formatting helpers

private fun formatDecimal(value: Double, decimals: Int = 2): String {
    val fmt = if (decimals == 0) "%.0f" else "%.${decimals}f"
    return String.format(java.util.Locale.US, fmt, value)
}
