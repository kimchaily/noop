package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.roundToInt

// MARK: - Fold posture (half-folded foldables)
//
// A book-style foldable can stand half-open. Held landscape on a table with the fold running across the screen,
// it is in TABLETOP posture: the upper half faces you like a little monitor, the lower half lies flat like a
// keyboard. Held upright half-open with the fold running top to bottom, it is in BOOK posture. Jetpack
// WindowManager reports both (and where the fold is); MainActivity collects it into [LocalFoldState].
//
// Screens with a single glanceable "hero" (Live's heart-rate vessel, Breathe's pacer) wrap themselves in
// [TabletopAware]: in tabletop posture the hero moves to the upper half, above the fold, and the rest of the
// screen scrolls on the lower half; flat or folded nothing changes. Book posture lines the More list pane up with
// the fold (see AppRoot).

/** How the device is folded right now. FLAT covers fully open, fully closed and non-foldable phones. */
enum class FoldPosture { FLAT, TABLETOP, BOOK }

/** The posture plus the fold's bounds in window pixels (zero-width for a seamless fold). */
@Immutable
data class FoldState(
    val posture: FoldPosture = FoldPosture.FLAT,
    val hingeLeftPx: Int = 0,
    val hingeTopPx: Int = 0,
    val hingeRightPx: Int = 0,
    val hingeBottomPx: Int = 0,
) {
    companion object {
        val Flat = FoldState()
    }
}

internal object FoldPostures {
    /** The posture for a fold that is [halfOpened] with its hinge running across ([horizontalHinge]) or down
     *  the screen. Fully open or closed is FLAT whichever way the hinge runs. */
    fun classify(halfOpened: Boolean, horizontalHinge: Boolean): FoldPosture = when {
        !halfOpened -> FoldPosture.FLAT
        horizontalHinge -> FoldPosture.TABLETOP
        else -> FoldPosture.BOOK
    }

    /** The fold state from WindowManager's layout info (no fold reported → FLAT). */
    fun from(info: WindowLayoutInfo): FoldState {
        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return FoldState.Flat
        val b = fold.bounds
        return FoldState(
            posture = classify(
                halfOpened = fold.state == FoldingFeature.State.HALF_OPENED,
                horizontalHinge = fold.orientation == FoldingFeature.Orientation.HORIZONTAL,
            ),
            hingeLeftPx = b.left,
            hingeTopPx = b.top,
            hingeRightPx = b.right,
            hingeBottomPx = b.bottom,
        )
    }

    /** Height (px) of the pane above the fold for a container whose top sits [containerTopPx] down the window
     *  and is [containerHeightPx] tall. Clamped to the middle half so a stray reading never squeezes a pane
     *  to nothing; an unmeasured container ([containerTopPx] < 0) splits evenly. */
    fun topPaneHeightPx(fold: FoldState, containerTopPx: Int, containerHeightPx: Int): Int {
        if (containerHeightPx <= 0) return 0
        val raw = if (containerTopPx < 0) containerHeightPx / 2 else fold.hingeTopPx - containerTopPx
        return raw.coerceIn(containerHeightPx / 4, containerHeightPx * 3 / 4)
    }
}

/** The current fold state, provided by MainActivity. FLAT everywhere a fold isn't half-open. */
val LocalFoldState = compositionLocalOf { FoldState.Flat }

/** True while the device stands half-open in tabletop posture. */
@Composable
fun isTabletop(): Boolean = LocalFoldState.current.posture == FoldPosture.TABLETOP

/**
 * Wraps a screen so that, in tabletop posture, its [hero] sits on the upper half above the fold (centred,
 * scrolling only if it is taller than the half) and the screen itself fills the lower half. Flat, folded or
 * with no hero it renders [content] alone. The screen keeps its place in the composition either way, so
 * standing the phone up or laying it flat never resets its scroll position or state; the screen should leave
 * its own copy of the hero out while [isTabletop] is true.
 */
@Composable
fun TabletopAware(
    hero: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val fold = LocalFoldState.current
    val split = hero != null && fold.posture == FoldPosture.TABLETOP
    var topPx by remember { mutableIntStateOf(-1) }
    var heightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { topPx = it.positionInWindow().y.roundToInt() }
            .onSizeChanged { heightPx = it.height },
    ) {
        if (split && hero != null) {
            val topPanePx = FoldPostures.topPaneHeightPx(fold, topPx, heightPx)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { topPanePx.toDp() })
                    .background(Palette.surfaceBase)
                    // Clear of a navigation rail floating over the edge (the background still runs under it).
                    .padding(
                        start = 28.dp + LocalNavRailInsets.current.start,
                        end = 28.dp + LocalNavRailInsets.current.end,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.verticalScroll(rememberScrollState())) { hero() }
            }
            // A physical hinge has a gap; a seamless fold reports none.
            val gapPx = (fold.hingeBottomPx - fold.hingeTopPx).coerceAtLeast(0)
            if (gapPx > 0) Spacer(Modifier.height(with(density) { gapPx.toDp() }))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content()
        }
    }
}
