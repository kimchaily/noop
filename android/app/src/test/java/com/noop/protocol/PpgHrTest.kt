package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/**
 * [PpgHr] — derive HR from the WHOOP 5/MG v26 optical PPG waveform by autocorrelation (#156).
 *
 * Internal ground truth (the Swift lane's method): a clean pulse-shaped signal autocorrelates
 * strongly at its period, so a synthetic 70 bpm sine at 24 Hz must recover ~70 bpm with high
 * confidence; white noise has no periodicity, so it must yield NO estimate (conf < 0.3). These two
 * cases pin both the frequency math (60·fs/lag) and the confidence gate.
 */
class PpgHrTest {

    private val fs = PpgHr.SAMPLE_RATE_HZ // 24

    /** Build [seconds] of a [bpm] sine at the 24 Hz grid, one [PpgHr.Sample] per sample. */
    private fun sine(bpm: Double, seconds: Int, baseTs: Long = 1_000_000L): List<PpgHr.Sample> {
        val freqHz = bpm / 60.0
        val total = seconds * fs
        return (0 until total).map { i ->
            val t = i.toDouble() / fs
            // Scale to ADC-count-ish integers; DC offset removed inside estimate().
            val v = (1000.0 * sin(2.0 * PI * freqHz * t)).toInt()
            PpgHr.Sample(ts = baseTs + (i.toLong() / fs), value = v)
        }
    }

    @Test
    fun recovers70BpmFromCleanSine() {
        // 16 s so several 8 s windows slide across it.
        val est = PpgHr.estimate(sine(bpm = 70.0, seconds = 16))
        assertTrue("expected at least one estimate from a clean sine", est.isNotEmpty())
        // Every window of a pure periodic signal should land within 2 bpm of the truth.
        for (e in est) {
            assertTrue("bpm ${e.bpm} not within 70±2", e.bpm in 68..72)
            assertTrue("confidence ${e.conf} below gate", e.conf >= PpgHr.MIN_CONFIDENCE)
            assertTrue("confidence ${e.conf} > 1", e.conf <= 1.0)
        }
    }

    @Test
    fun noiseYieldsNoEstimate() {
        val rng = Random(42)
        val noise = (0 until 16 * fs).map { i ->
            PpgHr.Sample(ts = 1_000_000L + (i.toLong() / fs), value = rng.nextInt(-1000, 1000))
        }
        val est = PpgHr.estimate(noise)
        // White noise has no periodic structure → autocorrelation never clears the 0.3 gate.
        assertTrue("noise produced estimates: $est", est.isEmpty())
    }

    @Test
    fun tooFewSamplesYieldsEmpty() {
        // A run shorter than 3 consecutive seconds cannot be estimated (mirrors Swift derivePpgHr,
        // which needs run.count >= 3 and a centred window of >= 3 s / 72 samples). 2 s → nothing.
        val short = sine(bpm = 70.0, seconds = 2) // 48 samples, run of 2
        assertEquals(emptyList<PpgHr.Estimate>(), PpgHr.estimate(short))
    }

    @Test
    fun recoversFromShortThreeSecondRun() {
        // Swift parity: a 3 s run DOES produce HR (each second's centred window holds >= 3 s). The
        // old Kotlin needed a full 8 s window and wrongly returned nothing here.
        val est = PpgHr.estimate(sine(bpm = 70.0, seconds = 3))
        assertTrue("expected estimates from a 3 s run", est.isNotEmpty())
        for (e in est) assertTrue("bpm ${e.bpm} not within 70±3", e.bpm in 67..73)
    }

    @Test
    fun prefersFundamentalNotHalfRate() {
        // A clean 50 bpm sine autocorrelates strongly at the true period AND at 2× the period
        // (25 bpm). The global-argmax estimator could lock onto the stronger low-lag-energy harmonic
        // and report ~25 bpm; fundamental-period preference must report ~50 (Swift parity, #219).
        val est = PpgHr.estimate(sine(bpm = 50.0, seconds = 16))
        assertTrue("expected estimates from a clean 50 bpm sine", est.isNotEmpty())
        for (e in est) assertTrue("bpm ${e.bpm} not near 50 (harmonic leak?)", e.bpm in 47..53)
    }

    @Test
    fun flatSignalYieldsEmpty() {
        val flat = (0 until 16 * fs).map { i ->
            PpgHr.Sample(ts = 1_000_000L + (i.toLong() / fs), value = 500)
        }
        // Zero variance → zero energy → no estimate (never a divide-by-zero).
        assertTrue(PpgHr.estimate(flat).isEmpty())
    }
}
