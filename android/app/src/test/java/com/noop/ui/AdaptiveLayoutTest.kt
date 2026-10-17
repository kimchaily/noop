package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the adaptive-layout maths behind the foldable / tablet support (AdaptiveLayout.kt). What must never
 * regress: a phone (and a foldable's folded cover screen) renders exactly as before — one column, 28dp
 * gutters — while a wide window (an unfolded Honor Magic V5 ≈ 852dp, Pixel Fold, Galaxy Z Fold, a tablet)
 * gets the columns the user chose, filled shortest-first so no column runs empty.
 */
class AdaptiveLayoutTest {

    private val auto = WideLayoutMode.AUTO

    @Test
    fun widthClassUsesMaterialBreakpoints() {
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.widthClass(411)) // phone / cover screen
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.widthClass(599))
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayout.widthClass(600))
        assertEquals(WindowWidthClass.MEDIUM, AdaptiveLayout.widthClass(839))
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveLayout.widthClass(840))
        assertEquals(WindowWidthClass.EXPANDED, AdaptiveLayout.widthClass(852)) // unfolded Magic V5
    }

    @Test
    fun compactWindowsAreAlwaysOneColumnWhateverTheSetting() {
        for (mode in WideLayoutMode.entries) {
            assertEquals("$mode @411", 1, AdaptiveLayout.columnsFor(mode, 411))
            assertEquals("$mode @599", 1, AdaptiveLayout.columnsFor(mode, 599))
        }
    }

    @Test
    fun automaticFitsPhoneWidthColumns() {
        assertEquals(2, AdaptiveLayout.columnsFor(auto, 852))  // unfolded Magic V5
        assertEquals(2, AdaptiveLayout.columnsFor(auto, 780))
        assertEquals(1, AdaptiveLayout.columnsFor(auto, 700))  // too narrow for two 340dp columns
        assertEquals(3, AdaptiveLayout.columnsFor(auto, 1280)) // landscape tablet
        assertEquals(3, AdaptiveLayout.columnsFor(auto, 2000)) // capped
    }

    @Test
    fun explicitChoicesWinOnWideWindows() {
        assertEquals(1, AdaptiveLayout.columnsFor(WideLayoutMode.ONE, 852))
        assertEquals(1, AdaptiveLayout.columnsFor(WideLayoutMode.ONE, 1280))
        assertEquals(2, AdaptiveLayout.columnsFor(WideLayoutMode.TWO, 640))
        assertEquals(2, AdaptiveLayout.columnsFor(WideLayoutMode.TWO, 1280))
    }

    @Test
    fun twoColumnsNeverSqueezesBelowReadableColumns() {
        // A screen beside the More list on an unfolded V5 gets ~531dp: two 230dp strips would be unreadable,
        // so even "Two columns" stays one there.
        assertEquals(1, AdaptiveLayout.columnsFor(WideLayoutMode.TWO, 852 - AdaptiveLayout.LIST_PANE_WIDTH_DP - 1))
        assertEquals(1, AdaptiveLayout.columnsFor(WideLayoutMode.TWO, 600))
    }

    @Test
    fun listDetailFromUnfoldedFoldablesUp() {
        assertFalse(AdaptiveLayout.usesListDetail(411))  // phone / cover screen
        assertFalse(AdaptiveLayout.usesListDetail(700))  // Pixel Fold held upright
        assertTrue(AdaptiveLayout.usesListDetail(720))
        assertTrue(AdaptiveLayout.usesListDetail(852))   // unfolded Magic V5
        assertTrue(AdaptiveLayout.usesListDetail(1280))
    }

    @Test
    fun storageRoundTripsAndDefaultsToAutomatic() {
        for (mode in WideLayoutMode.entries) assertEquals(mode, WideLayoutMode.fromStorage(mode.storageValue))
        assertEquals(auto, WideLayoutMode.fromStorage(null))
        assertEquals(auto, WideLayoutMode.fromStorage("bogus"))
    }

    @Test
    fun phonesKeepThePlainGutter() {
        val cap = AdaptiveLayout.READABLE_MAX_WIDTH_DP
        for (w in listOf(320f, 360f, 411f, 599f, cap + 56f)) {
            assertEquals("window $w", 28f, AdaptiveLayout.sideInsetDp(w, cap), 0.001f)
        }
    }

    @Test
    fun wideWindowsCentreTheCappedContent() {
        val cap = AdaptiveLayout.READABLE_MAX_WIDTH_DP
        assertEquals(280f, AdaptiveLayout.sideInsetDp(1280f, cap), 0.001f)
        // An unfolded Magic V5 in two columns uses the full width (the grid cap is far wider).
        assertEquals(28f, AdaptiveLayout.sideInsetDp(852f, AdaptiveLayout.gridMaxWidthDp(2)), 0.001f)
        // Onboarding: a 24dp gutter and a 560dp column.
        assertEquals(24f, AdaptiveLayout.sideInsetDp(411f, 560f, minGutterDp = 24f), 0.001f)
        assertEquals(146f, AdaptiveLayout.sideInsetDp(852f, 560f, minGutterDp = 24f), 0.001f)
    }

    // --- Masonry placement ---

    private fun flow(heights: List<Int>, columns: Int, spanAll: Set<Int> = emptySet(), spacing: Int = 10) =
        AdaptiveLayout.flowSlots(
            heights.toIntArray(),
            BooleanArray(heights.size) { it in spanAll },
            columns,
            spacing,
        )

    @Test
    fun oneColumnStacksLikeAColumn() {
        val (slots, total) = flow(listOf(100, 50, 30), columns = 1)
        assertEquals(listOf(0, 110, 170), slots.map { it.y })
        assertEquals(200, total)
    }

    @Test
    fun eachCardGoesIntoTheShortestColumn() {
        // The screenshot case: a tall "Your cards" beside short sections — the short ones all stack on the
        // right instead of alternating and leaving the right column empty.
        val (slots, total) = flow(listOf(300, 1200, 250, 200, 400, 150), columns = 2)
        assertEquals(listOf(0, 1, 0, 0, 0, 0), slots.map { it.column })
        assertEquals(listOf(0, 0, 310, 570, 780, 1190), slots.map { it.y })
        assertEquals(1340, total)
    }

    @Test
    fun tiesGoLeftAndOrderIsKept() {
        val (slots, _) = flow(listOf(100, 100, 100, 100), columns = 2)
        assertEquals(listOf(0, 1, 0, 1), slots.map { it.column })
        assertEquals(listOf(0, 0, 110, 110), slots.map { it.y })
    }

    @Test
    fun spanAllStartsBelowEveryColumn() {
        // header (span) · two cards · a span banner · one more card.
        val (slots, total) = flow(listOf(80, 300, 100, 60, 50), columns = 2, spanAll = setOf(0, 3))
        assertEquals(AdaptiveLayout.SPAN_ALL, slots[0].column)
        assertEquals(0, slots[0].y)
        assertEquals(listOf(0, 1), listOf(slots[1].column, slots[2].column))
        assertEquals(90, slots[1].y)
        assertEquals(AdaptiveLayout.SPAN_ALL, slots[3].column)
        assertEquals(400, slots[3].y) // below the taller column (90 + 300) + spacing
        assertEquals(0, slots[4].column)
        assertEquals(470, slots[4].y)
        assertEquals(520, total)
    }

    @Test
    fun emptyCardsTakeNoSpace() {
        val (slots, total) = flow(listOf(100, 0, 100), columns = 1)
        assertEquals(listOf(0, 0, 110), slots.map { it.y })
        assertEquals(210, total)
    }

    @Test
    fun threeColumnsFillEvenly() {
        val (slots, _) = flow(listOf(100, 100, 100, 50, 50, 50), columns = 3)
        assertEquals(listOf(0, 1, 2, 0, 1, 2), slots.map { it.column })
    }
}
