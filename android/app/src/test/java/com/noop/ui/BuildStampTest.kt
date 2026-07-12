package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Today-header build stamp: stable stays a calm bare version; preview carries the full
 * "which build is this?" trail (version · build code · branch@sha), degrading gracefully when CI
 * metadata is absent (local builds).
 */
class BuildStampTest {

    @Test
    fun stableIsJustTheVersion() {
        assertEquals("v8.2.6", buildStamp("stable", "8.2.6", 265, "main", "abc1234"))
        // Even with metadata present, stable never surfaces channel/build internals.
        assertEquals("v8.2.6", buildStamp("stable", "8.2.6", 265, "", ""))
    }

    @Test
    fun previewCarriesBuildAndSource() {
        assertEquals(
            "v8.2.6-preview · build 1016 · feature/x@71171fd",
            buildStamp("preview", "8.2.6-preview", 1016, "feature/x", "71171fd"),
        )
    }

    @Test
    fun previewDegradesWhenCiMetadataMissing() {
        // Local preview build: no branch/sha injected — omit the segment, never render "@".
        assertEquals("v8.2.6-preview · build 1016", buildStamp("preview", "8.2.6-preview", 1016, "", ""))
        // Only one half present → show what exists.
        assertEquals("v8.2.6-preview · build 1016 · 71171fd", buildStamp("preview", "8.2.6-preview", 1016, "", "71171fd"))
        assertEquals("v8.2.6-preview · build 1016 · feature/x", buildStamp("preview", "8.2.6-preview", 1016, "feature/x", ""))
    }
}
