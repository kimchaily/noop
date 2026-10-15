package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the adaptive-layout maths behind the foldable / tablet support (AdaptiveLayout.kt). What must never
 * regress: a phone (and a foldable's folded cover screen) renders exactly as before — one column, 28dp
 * gutters — while a wide window (an unfolded Honor Magic V5, Pixel Fold, Galaxy Z Fold, or a tablet) gets two
 * Today lanes and centred, capped content.
 */
class AdaptiveLayoutTest {

    @Test
    fun widthClassUsesMaterialBreakpoints() {
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.widthClass(0))
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.widthClass(411)) // typical phone / cover screen
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.widthClass(599))
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayout.widthClass(600))
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayout.widthClass(780)) // unfolded book-style foldable
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayout.widthClass(839))
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveLayout.widthClass(840))
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveLayout.widthClass(1280)) // tablet landscape
    }

    @Test
    fun twoLanesOnlyFromMediumUp() {
        assertFalse(AdaptiveLayout.usesTwoLanes(360))
        assertFalse(AdaptiveLayout.usesTwoLanes(411))
        assertFalse(AdaptiveLayout.usesTwoLanes(599))
        assertTrue(AdaptiveLayout.usesTwoLanes(600))
        assertTrue(AdaptiveLayout.usesTwoLanes(780))
        assertTrue(AdaptiveLayout.usesTwoLanes(1280))
    }

    @Test
    fun phonesKeepThePlainGutter() {
        // Every window up to cap + 2 × 28dp keeps the original 28dp gutters, so no phone layout shifts.
        val cap = AdaptiveLayout.READABLE_MAX_WIDTH_DP
        for (w in listOf(320f, 360f, 411f, 480f, 599f, cap, cap + 56f)) {
            assertEquals("window $w", 28f, AdaptiveLayout.sideInsetDp(w, cap), 0.001f)
        }
    }

    @Test
    fun wideWindowsCentreTheCappedContent() {
        val cap = AdaptiveLayout.READABLE_MAX_WIDTH_DP
        // 1280dp tablet: (1280 − 720) / 2 = 280dp each side → exactly 720dp of content, centred.
        val inset = AdaptiveLayout.sideInsetDp(1280f, cap)
        assertEquals(280f, inset, 0.001f)
        assertEquals(cap, 1280f - 2 * inset, 0.001f)
        // Just past the threshold the margins grow smoothly from the gutter, no jump.
        assertEquals(30f, AdaptiveLayout.sideInsetDp(cap + 60f, cap), 0.001f)
    }

    @Test
    fun customGutterAndCapForOnboarding() {
        // Onboarding uses a 24dp gutter and a 560dp column: phone unchanged, unfolded foldable centred.
        assertEquals(24f, AdaptiveLayout.sideInsetDp(411f, 560f, minGutterDp = 24f), 0.001f)
        assertEquals(110f, AdaptiveLayout.sideInsetDp(780f, 560f, minGutterDp = 24f), 0.001f)
    }

    @Test
    fun dashboardCapLeavesAnUnfoldedFoldableFullWidth() {
        // A ~780–900dp inner screen is under the dashboard cap, so both Today lanes use the whole width.
        for (w in listOf(700f, 780f, 841f, 900f)) {
            assertEquals("window $w", 28f, AdaptiveLayout.sideInsetDp(w, AdaptiveLayout.DASHBOARD_MAX_WIDTH_DP), 0.001f)
        }
    }

    @Test
    fun dealIntoLanesAlternatesInReadingOrder() {
        val (left, right) = AdaptiveLayout.dealIntoLanes(listOf("hero", "hr", "cards", "synthesis", "vitals", "metrics", "workouts"))
        assertEquals(listOf("hero", "cards", "vitals", "workouts"), left)
        assertEquals(listOf("hr", "synthesis", "metrics"), right)
    }

    @Test
    fun dealIntoLanesHandlesSmallLists() {
        assertEquals(emptyList<String>() to emptyList<String>(), AdaptiveLayout.dealIntoLanes(emptyList<String>()))
        assertEquals(listOf("only") to emptyList<String>(), AdaptiveLayout.dealIntoLanes(listOf("only")))
        assertEquals(listOf("a") to listOf("b"), AdaptiveLayout.dealIntoLanes(listOf("a", "b")))
    }

    @Test
    fun dealIntoLanesKeepsEveryItemExactlyOnce() {
        val items = (1..11).toList()
        val (left, right) = AdaptiveLayout.dealIntoLanes(items)
        assertEquals(items, (left + right).sorted())
        assertEquals(6, left.size)
        assertEquals(5, right.size)
    }
}
