package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The LineChart tap/drag pinpoint label (#463). The overlay used to draw the RAW plotted value
 * unconditionally, so on Trends' Effort chart a tapped day printed the stored 0-100 figure (a bare
 * "13") beside a 0-21 converted axis. Callers can now inject the same formatter the axis uses;
 * with no formatter the raw near-integer-collapsing default is unchanged.
 */
class ChartSelectionLabelTest {

    @Test fun withoutAFormatterTheRawDefaultIsUnchanged() {
        // Near-integer values collapse to the bare integer, the exact "13" the reporter saw.
        assertEquals("13", lineChartSelectionLabel(13.0, null))
        assertEquals("13", lineChartSelectionLabel(12.98, null))
        // Clearly fractional values keep one decimal.
        assertEquals("9.4", lineChartSelectionLabel(9.4, null))
    }

    @Test fun aSuppliedFormatterOwnsTheLabel() {
        val toWhoopScale: (Double) -> String = { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) }
        // The stored 13 renders as 2.7 on the 0-21 display scale, matching the axis column.
        assertEquals("2.7", lineChartSelectionLabel(13.0, toWhoopScale))
    }

    @Test fun theFormatterReceivesThePlottedValueVerbatim() {
        var seen: Double? = null
        lineChartSelectionLabel(41.5, { v -> seen = v; "x" })
        assertEquals(41.5, seen!!, 0.0)
    }

    // MARK: - The x-axis half of the read-out
    //
    // A tapped point answered only "how much"; the "when" had to be eyeballed against the three axis
    // ticks under the chart (and the Vital Signs / Stress cards have no date axis at all). The label
    // now carries the sample's own x value when the caller supplies one.

    @Test fun anXLabelIsAppendedAfterTheValue() {
        assertEquals("72.1 · 17:20", lineChartSelectionLabel(72.1, null, "17:20"))
        assertEquals("54 · 9 Aug", lineChartSelectionLabel(54.0, null, "9 Aug"))
    }

    @Test fun theXLabelRidesAlongsideACallersValueFormatter() {
        val toWhoopScale: (Double) -> String = { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) }
        // Both halves stay in their own display space: the converted value, the axis-worded day.
        assertEquals("2.7 · 19 Jul", lineChartSelectionLabel(13.0, toWhoopScale, "19 Jul"))
    }

    @Test fun aMissingOrBlankXLabelLeavesTheOldValueOnlyLabel() {
        assertEquals("13", lineChartSelectionLabel(13.0, null, null))
        assertEquals("13", lineChartSelectionLabel(13.0, null, ""))
        assertEquals("13", lineChartSelectionLabel(13.0, null, "   "))
    }

    // MARK: - Label alignment across non-finite holes
    //
    // The charts drop non-finite samples before plotting, so labels indexed against the RAW series
    // would slide off by one per hole — a tapped point would name the wrong day/time.

    @Test fun labelsAreRealignedToTheFiniteSamplesTheChartPlots() {
        val values = listOf(1.0, Double.NaN, 3.0, 4.0)
        val labels = listOf("Mon", "Tue", "Wed", "Thu")
        // "Tue" is dropped with its NaN, so the plotted samples read Mon / Wed / Thu in order.
        assertEquals(listOf("Mon", "Wed", "Thu"), alignLabelsToFiniteValues(values, labels))
    }

    @Test fun noLabelsInMeansNoLabelsOut() {
        assertNull(alignLabelsToFiniteValues(listOf(1.0, 2.0), null))
    }

    @Test fun aShortOrEmptyLabelListDegradesToBlanksRatherThanCrashing() {
        // Trends' ChartCard defaults `dates` to an empty list; the chart must still draw its values.
        assertEquals(listOf("", ""), alignLabelsToFiniteValues(listOf(1.0, 2.0), emptyList()))
        assertEquals(listOf("Mon", ""), alignLabelsToFiniteValues(listOf(1.0, 2.0), listOf("Mon")))
        // …and a blank slot simply falls back to the value-only label.
        assertEquals("2", lineChartSelectionLabel(2.0, null, ""))
    }
}
