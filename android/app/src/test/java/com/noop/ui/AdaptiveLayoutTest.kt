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
    fun layoutButtonOnlyWhereTwoColumnsFit() {
        assertFalse(AdaptiveLayout.canSplit(411))                                        // phone
        assertFalse(AdaptiveLayout.canSplit(852 - AdaptiveLayout.LIST_PANE_WIDTH_DP - 1)) // beside the More list
        assertTrue(AdaptiveLayout.canSplit(852))                                         // unfolded V5, full width
    }

    @Test
    fun sideRailOnWideWindowsUnlessTheBarIsChosen() {
        assertFalse(AdaptiveLayout.usesNavRail(411, railPreferred = true))  // phone / cover screen
        assertTrue(AdaptiveLayout.usesNavRail(600, railPreferred = true))
        assertTrue(AdaptiveLayout.usesNavRail(852, railPreferred = true))   // unfolded V5
        assertFalse(AdaptiveLayout.usesNavRail(852, railPreferred = false)) // "Bottom bar" in Settings
        // With the rail the More list still fits beside a screen on an unfolded V5.
        assertTrue(AdaptiveLayout.usesListDetail(852 - AdaptiveLayout.RAIL_WIDTH_DP))
        // And Today still gets two columns beside the rail.
        assertEquals(2, AdaptiveLayout.columnsFor(WideLayoutMode.AUTO, 852 - AdaptiveLayout.RAIL_WIDTH_DP))
    }

    @Test
    fun navShortcutsStoreCleanly() {
        assertEquals("trends,sleep,live", AdaptiveLayout.encodeNavShortcuts(listOf("trends", "sleep", "live")))
        assertEquals(listOf("trends", "sleep", "live"), AdaptiveLayout.decodeNavShortcuts("trends, sleep,,trends,live"))
        assertEquals(emptyList<String>(), AdaptiveLayout.decodeNavShortcuts("")) // "no shortcuts" is a valid choice
        val many = (1..12).joinToString(",") { "r$it" }
        assertEquals(AdaptiveLayout.MAX_NAV_SHORTCUTS, AdaptiveLayout.decodeNavShortcuts(many).size)
        assertEquals(listOf("trends", "sleep", "live", "workouts", "health"), AdaptiveLayout.DEFAULT_NAV_SHORTCUTS)
    }

    @Test
    fun railFitsWhatTheHeightAllows() {
        // N slots need 56·N + 93dp.
        assertEquals(13, AdaptiveLayout.railSlotCapacity(836f)) // unfolded V5 held upright
        assertEquals(5, AdaptiveLayout.railSlotCapacity(410f))  // half-open on a table: the lower half only
        assertEquals(2, AdaptiveLayout.railSlotCapacity(50f))   // never fewer than Today + More
    }

    @Test
    fun barFitsWhatTheWidthAllows() {
        assertEquals(11, AdaptiveLayout.barSlotCapacity(852f))
        assertEquals(7, AdaptiveLayout.barSlotCapacity(600f))
        assertEquals(2, AdaptiveLayout.barSlotCapacity(100f))
    }

    @Test
    fun fitKeepsTodayAndTheFirstShortcutsLeavingRoomForMore() {
        val tabs = listOf("today", "trends", "sleep", "live", "workouts", "health")
        assertEquals(listOf("today", "trends", "sleep", "live"), AdaptiveLayout.fitNavTabs(tabs, 5))
        assertEquals(tabs, AdaptiveLayout.fitNavTabs(tabs, 13))
        assertEquals(listOf("today"), AdaptiveLayout.fitNavTabs(tabs, 2))
        assertEquals(listOf("today"), AdaptiveLayout.fitNavTabs(tabs, 1))
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
