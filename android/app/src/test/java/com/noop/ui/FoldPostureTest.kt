package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the half-folded posture handling (FoldPosture.kt): which way a half-open fold counts as tabletop vs book,
 * where the upper pane ends, and how the More list lines up with a book-posture fold.
 */
class FoldPostureTest {

    @Test
    fun fullyOpenOrClosedIsFlatWhicheverWayTheHingeRuns() {
        assertEquals(FoldPosture.FLAT, FoldPostures.classify(halfOpened = false, horizontalHinge = true))
        assertEquals(FoldPosture.FLAT, FoldPostures.classify(halfOpened = false, horizontalHinge = false))
    }

    @Test
    fun halfOpenAcrossTheScreenIsTabletopDownItIsBook() {
        assertEquals(FoldPosture.TABLETOP, FoldPostures.classify(halfOpened = true, horizontalHinge = true))
        assertEquals(FoldPosture.BOOK, FoldPostures.classify(halfOpened = true, horizontalHinge = false))
    }

    @Test
    fun upperPaneEndsAtTheFold() {
        // Container starts 100px down the window, fold at 1100px → 1000px above the fold.
        val fold = FoldState(FoldPosture.TABLETOP, hingeTopPx = 1100, hingeBottomPx = 1100)
        assertEquals(1000, FoldPostures.topPaneHeightPx(fold, containerTopPx = 100, containerHeightPx = 2000))
    }

    @Test
    fun upperPaneIsClampedAndSplitsEvenlyBeforeMeasuring() {
        val fold = FoldState(FoldPosture.TABLETOP, hingeTopPx = 50)
        assertEquals(500, FoldPostures.topPaneHeightPx(fold, containerTopPx = 0, containerHeightPx = 2000))   // ≥ 1/4
        val low = FoldState(FoldPosture.TABLETOP, hingeTopPx = 1990)
        assertEquals(1500, FoldPostures.topPaneHeightPx(low, containerTopPx = 0, containerHeightPx = 2000))   // ≤ 3/4
        assertEquals(1000, FoldPostures.topPaneHeightPx(fold, containerTopPx = -1, containerHeightPx = 2000)) // unmeasured
        assertEquals(0, FoldPostures.topPaneHeightPx(fold, containerTopPx = 0, containerHeightPx = 0))
    }

    @Test
    fun listPaneFollowsABookFoldWithinLimits() {
        assertEquals(AdaptiveLayout.LIST_PANE_WIDTH_DP, AdaptiveLayout.listPaneWidthDp(null))
        assertEquals(426, AdaptiveLayout.listPaneWidthDp(426)) // Magic V5 fold at the middle of ~852dp
        assertEquals(280, AdaptiveLayout.listPaneWidthDp(120))
        assertEquals(480, AdaptiveLayout.listPaneWidthDp(700))
    }
}
