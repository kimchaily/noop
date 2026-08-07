package com.noop.protocol

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit tests for [PpgHr] — HR estimation from the WHOOP 5.0 v26 PPG waveform
 * via autocorrelation.
 */
class PpgHrTest {

    /** Generate a synthetic PPG signal at a known heart rate. */
    private fun syntheticPpg(seconds: Int, bpm: Int, fs: Int = PpgHr.SAMPLE_RATE_HZ): List<PpgHr.Sample> {
        val samples = ArrayList<PpgHr.Sample>(seconds * fs)
        val beatsPerSec = bpm / 60.0
        for (s in 0 until seconds) {
            for (i in 0 until fs) {
                val t = s + i.toDouble() / fs
                // Pulse waveform: a sinusoid at the heart rate + some DC
                val phase = 2.0 * PI * beatsPerSec * t
                val value = (sin(phase) * 1000 + 2000).toInt()
                samples.add(PpgHr.Sample(ts = s.toLong(), value = value))
            }
        }
        return samples
    }

    @Test fun estimate_emptyInput() {
        assertTrue(PpgHr.estimate(emptyList()).isEmpty())
    }

    @Test fun estimate_synthetic60bpm() {
        // 10 seconds of clean 60 bpm PPG → should detect ~60 bpm
        val samples = syntheticPpg(seconds = 10, bpm = 60)
        val estimates = PpgHr.estimate(samples)
        // Should produce at least one estimate (the centred windows in the middle of the run)
        assertTrue(estimates.isNotEmpty())
        // All estimates should be near 60 bpm
        for (e in estimates) {
            assertTrue("bpm ${e.bpm} not near 60", e.bpm in 55..65)
        }
    }

    @Test fun estimate_synthetic90bpm() {
        val samples = syntheticPpg(seconds = 10, bpm = 90)
        val estimates = PpgHr.estimate(samples)
        assertTrue(estimates.isNotEmpty())
        for (e in estimates) {
            assertTrue("bpm ${e.bpm} not near 90", e.bpm in 82..98)
        }
    }

    @Test fun estimate_synthetic120bpm() {
        val samples = syntheticPpg(seconds = 10, bpm = 120)
        val estimates = PpgHr.estimate(samples)
        assertTrue(estimates.isNotEmpty())
        for (e in estimates) {
            assertTrue("bpm ${e.bpm} not near 120", e.bpm in 110..130)
        }
    }

    @Test fun estimate_tooFewSeconds() {
        // Only 2 seconds — below the 3-second minimum window
        val samples = syntheticPng(seconds = 2, bpm = 60)
        assertTrue(PpgHr.estimate(samples).isEmpty())
    }

    private fun syntheticPng(seconds: Int, bpm: Int): List<PpgHr.Sample> =
        syntheticPpg(seconds, bpm)

    @Test fun estimate_gappedRun() {
        // Two runs: 0..4 and 10..14, each at 60 bpm
        val run1 = syntheticPpg(seconds = 5, bpm = 60)
        val run2 = syntheticPpg(seconds = 5, bpm = 60).map { it.copy(ts = it.ts + 10) }
        val estimates = PpgHr.estimate(run1 + run2)
        // Should produce estimates for both runs (not across the gap)
        assertTrue(estimates.isNotEmpty())
        for (e in estimates) {
            assertTrue("bpm ${e.bpm} not near 60", e.bpm in 55..65)
        }
    }

    @Test fun estimate_flatSignalNoEstimate() {
        // A flat signal has no pulse → autocorrelation peak won't clear MIN_CONFIDENCE
        val samples = (0 until 10).flatMap { s ->
            (0 until PpgHr.SAMPLE_RATE_HZ).map { i ->
                PpgHr.Sample(ts = s.toLong(), value = 2000)
            }
        }
        val estimates = PpgHr.estimate(samples)
        // A flat signal should not produce a confident HR estimate
        assertTrue(estimates.isEmpty())
    }

    @Test fun estimate_confidenceInRange() {
        val samples = syntheticPpg(seconds = 10, bpm = 72)
        val estimates = PpgHr.estimate(samples)
        for (e in estimates) {
            assertTrue("conf ${e.conf} < 0", e.conf >= 0.0)
            assertTrue("conf ${e.conf} > 1", e.conf <= 1.0)
        }
    }

    @Test fun constants_sane() {
        assertEquals(24, PpgHr.SAMPLE_RATE_HZ)
        assertEquals(8, PpgHr.WINDOW_SECONDS)
        assertEquals(30.0, PpgHr.MIN_BPM, 0.001)
        assertEquals(220.0, PpgHr.MAX_BPM, 0.001)
        assertEquals(0.3, PpgHr.MIN_CONFIDENCE, 0.001)
    }
}
