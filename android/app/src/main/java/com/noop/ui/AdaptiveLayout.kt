package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

// MARK: - Adaptive layout (foldables · tablets · landscape)
//
// Choop was laid out for one phone-width column. On a book-style foldable's inner screen (Honor Magic V5,
// Pixel Fold, Galaxy Z Fold — roughly 700–900dp wide and nearly square) that column stretched every card
// edge to edge. This file is the one place that decides how the shell adapts to the window it is given:
//
//   • Window width class — the Material 3 breakpoints (compact < 600dp ≤ medium < 840dp ≤ expanded). The
//     folded cover screen and any phone in portrait are COMPACT, so phones render exactly as before.
//   • Two lanes — wide windows (medium+) lay the Today dashboard out as two phone-width columns of cards
//     instead of one stretched column. Each lane is about a cover-screen wide, so every card keeps the
//     proportions it was designed at, and the gutter between the lanes lands on the hinge.
//   • Readable width — every other screen keeps one column but centres it and caps its width, so text and
//     charts don't run across a tablet. The cap only engages once the window is wider than the cap plus the
//     standard 28dp screen gutters, which no phone is.
//
// The decision reads [LocalConfiguration], which tracks the window (split-screen, freeform, fold/unfold)
// rather than the physical display. MainActivity handles size/orientation changes itself (see the manifest's
// configChanges), so folding or unfolding recomposes in place instead of recreating the activity.

/** Material 3 window width classes. */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** The pure breakpoints and maths behind the adaptive shell — no Compose state, unit-tested on the JVM. */
internal object AdaptiveLayout {
    /** First width (dp) that is no longer a phone column: the medium class. */
    const val MEDIUM_MIN_WIDTH_DP = 600

    /** First width (dp) of the expanded class. */
    const val EXPANDED_MIN_WIDTH_DP = 840

    /** The standard screen gutter both scaffolds use on a phone. */
    const val SCREEN_GUTTER_DP = 28f

    /** Widest a single-column screen's content grows before it is centred (a comfortable reading width). */
    const val READABLE_MAX_WIDTH_DP = 720f

    /** Widest the two-lane Today dashboard grows: each lane tops out a little under 520dp on a big tablet. */
    const val DASHBOARD_MAX_WIDTH_DP = 1040f

    fun widthClass(widthDp: Int): WindowWidthClass = when {
        widthDp < MEDIUM_MIN_WIDTH_DP -> WindowWidthClass.COMPACT
        widthDp < EXPANDED_MIN_WIDTH_DP -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }

    /** True when the window is wide enough for two side-by-side card lanes (an unfolded foldable, a tablet,
     *  a phone in landscape). A folded cover screen or a phone in portrait stays on one column. */
    fun usesTwoLanes(widthDp: Int): Boolean = widthClass(widthDp) != WindowWidthClass.COMPACT

    /**
     * The left/right inset that centres content of at most [maxContentWidthDp] in a window [windowWidthDp]
     * wide, never tighter than the phone gutter [minGutterDp]. Any window up to `max + 2 × gutter` wide gets
     * the plain gutter, so phones are untouched; wider windows grow equal margins instead of wider cards.
     */
    fun sideInsetDp(
        windowWidthDp: Float,
        maxContentWidthDp: Float,
        minGutterDp: Float = SCREEN_GUTTER_DP,
    ): Float = max(minGutterDp, (windowWidthDp - maxContentWidthDp) / 2f)

    /**
     * Deal [items] into two lanes in reading order — 1st left, 2nd right, 3rd left, … — so the user's own
     * order (Settings → Appearance → Today layout) still reads top-to-bottom, left-to-right, and a section that
     * grows (an expanded card) only pushes down its OWN lane instead of hopping a neighbour across. Strict
     * alternation is deliberately not height-balanced: it is predictable, and the user can rebalance by
     * reordering.
     */
    fun <T> dealIntoLanes(items: List<T>): Pair<List<T>, List<T>> {
        val left = ArrayList<T>((items.size + 1) / 2)
        val right = ArrayList<T>(items.size / 2)
        items.forEachIndexed { i, item -> if (i % 2 == 0) left += item else right += item }
        return left to right
    }
}

/** Whether the current window lays card dashboards out in two lanes (see [AdaptiveLayout.usesTwoLanes]). */
@Composable
fun usesTwoLanes(): Boolean = AdaptiveLayout.usesTwoLanes(LocalConfiguration.current.screenWidthDp)

/** The left/right screen inset for a screen whose content should not grow wider than [maxContentWidth]:
 *  the plain [minGutter] on a phone, centred margins on a wide window. */
@Composable
fun adaptiveSideInset(
    maxContentWidth: Dp,
    minGutter: Dp = AdaptiveLayout.SCREEN_GUTTER_DP.dp,
): Dp =
    AdaptiveLayout.sideInsetDp(
        windowWidthDp = LocalConfiguration.current.screenWidthDp.toFloat(),
        maxContentWidthDp = maxContentWidth.value,
        minGutterDp = minGutter.value,
    ).dp

/** One section placed into a lane: a stable [key] (so a section keeps its state when a neighbour is shown,
 *  hidden or reordered) and the blocks it renders top-to-bottom. */
class LaneSection(val key: Any, val blocks: List<@Composable () -> Unit>)

/**
 * Two top-aligned columns of sections, dealt by [AdaptiveLayout.dealIntoLanes]. Every block keeps the same
 * vertical [spacing] it had as its own row in the single-column list, and the gutter between the lanes uses
 * the same value so the grid reads evenly in both directions.
 */
@Composable
fun TwoLaneSections(
    sections: List<LaneSection>,
    spacing: Dp,
    modifier: Modifier = Modifier,
) {
    val (left, right) = AdaptiveLayout.dealIntoLanes(sections)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Top,
    ) {
        LaneColumn(left, spacing, Modifier.weight(1f))
        LaneColumn(right, spacing, Modifier.weight(1f))
    }
}

@Composable
private fun LaneColumn(sections: List<LaneSection>, spacing: Dp, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        sections.forEach { section ->
            key(section.key) {
                section.blocks.forEach { block -> block() }
            }
        }
    }
}

/** Default content cap for single-column screens (see [AdaptiveLayout.READABLE_MAX_WIDTH_DP]). */
val ReadableMaxContentWidth: Dp = AdaptiveLayout.READABLE_MAX_WIDTH_DP.dp

/** Content cap for the two-lane Today dashboard (see [AdaptiveLayout.DASHBOARD_MAX_WIDTH_DP]). */
val DashboardMaxContentWidth: Dp = AdaptiveLayout.DASHBOARD_MAX_WIDTH_DP.dp
