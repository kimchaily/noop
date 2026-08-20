package com.noop.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [Streams] extraction and [skinTempCelsius] conversion.
 */
class StreamsTest {

    // ── extractStreams ─────────────────────────────────────────────────────

    @Test fun extractStreams_emptyInput() {
        val streams = extractStreams(emptyList(), 0, 0)
        assertTrue(streams.hr.isEmpty())
        assertTrue(streams.rr.isEmpty())
        assertTrue(streams.events.isEmpty())
        assertTrue(streams.battery.isEmpty())
    }

    @Test fun extractStreams_skipsCrcFailedFrames() {
        // Build a frame with a bad CRC32
        val inner = byteArrayOf(40, 0, 0, 0x78.toByte(), 0x56.toByte(), 0x34, 0x12, 0, 0, 72, 0)
        val length = inner.size + 4
        val lenLo = (length and 0xFF).toByte()
        val lenHi = ((length ushr 8) and 0xFF).toByte()
        val headerCrc = Crc.crc8(byteArrayOf(lenLo, lenHi)).toByte()
        val frame = ByteArray(1 + 2 + 1 + inner.size + 4)
        var i = 0
        frame[i++] = 0xAA.toByte()
        frame[i++] = lenLo; frame[i++] = lenHi; frame[i++] = headerCrc
        System.arraycopy(inner, 0, frame, i, inner.size); i += inner.size
        // Deliberately wrong CRC32
        frame[i++] = 0xDE.toByte(); frame[i++] = 0xAD.toByte(); frame[i++] = 0xBE.toByte(); frame[i] = 0xEF.toByte()

        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP4)
        val streams = extractStreams(listOf(parsed), 0, 0)
        // CRC-failed frame should produce no HR
        assertTrue(streams.hr.isEmpty())
    }

    @Test fun extractStreams_realtimeData() {
        // Build a valid REALTIME_DATA frame with HR=72, ts=1000
        val inner = ByteArray(12)
        inner[0] = 40 // REALTIME_DATA
        inner[1] = 0  // seq
        inner[2] = 0xE8.toByte(); inner[3] = 0x03; inner[4] = 0; inner[5] = 0 // ts=1000 (0x3E8)
        inner[6] = 0; inner[7] = 0 // subseconds
        inner[8] = 72 // heart_rate
        inner[9] = 2  // rr_count
        inner[10] = 0xE8.toByte(); inner[11] = 0x03 // rr=1000ms

        val length = inner.size + 4
        val lenLo = (length and 0xFF).toByte()
        val lenHi = ((length ushr 8) and 0xFF).toByte()
        val headerCrc = Crc.crc8(byteArrayOf(lenLo, lenHi)).toByte()
        val trailer = Crc.crc32(inner)

        val frame = ByteArray(1 + 2 + 1 + inner.size + 4)
        var i = 0
        frame[i++] = 0xAA.toByte()
        frame[i++] = lenLo; frame[i++] = lenHi; frame[i++] = headerCrc
        System.arraycopy(inner, 0, frame, i, inner.size); i += inner.size
        frame[i++] = (trailer and 0xFFL).toByte()
        frame[i++] = ((trailer ushr 8) and 0xFFL).toByte()
        frame[i++] = ((trailer ushr 16) and 0xFFL).toByte()
        frame[i] = ((trailer ushr 24) and 0xFFL).toByte()

        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP4)
        // deviceClockRef = 0, wallClockRef = 0 → ts maps directly
        val streams = extractStreams(listOf(parsed), deviceClockRef = 0, wallClockRef = 0)
        assertEquals(1, streams.hr.size)
        assertEquals(72, streams.hr[0].bpm)
        assertEquals(1000, streams.hr[0].ts)
        assertEquals(2, streams.rr.size)
        assertEquals(1000, streams.rr[0].rrMs)
    }

    // ── skinTempCelsius ────────────────────────────────────────────────────

    @Test fun skinTempCelsius_whoop5_centiDegrees() {
        // WHOOP5: raw / 100
        assertEquals(30.57, skinTempCelsius(3057, DeviceFamily.WHOOP5), 0.01)
        assertEquals(22.47, skinTempCelsius(2247, DeviceFamily.WHOOP5), 0.01)
    }

    @Test fun skinTempCelsius_whoop4_anchorMapping() {
        // WHOOP4: affine map anchored at raw 826 → 33.0 °C, slope 0.05
        assertEquals(33.0, skinTempCelsius(826, DeviceFamily.WHOOP4), 0.01)
        // One raw unit above anchor → +0.05 °C
        assertEquals(33.05, skinTempCelsius(827, DeviceFamily.WHOOP4), 0.01)
        // One raw unit below anchor → -0.05 °C
        assertEquals(32.95, skinTempCelsius(825, DeviceFamily.WHOOP4), 0.01)
    }

    @Test fun skinTempCelsius_whoop4_wornRange() {
        // A typical worn value ~830-865 should map to a physiological range
        val celsius = skinTempCelsius(850, DeviceFamily.WHOOP4)
        assertTrue("worn temp $celsius should be in 28-42 °C", celsius in 28.0..42.0)
    }

    // ── Whoop4SkinTemp constants ───────────────────────────────────────────

    @Test fun whoop4SkinTemp_constants() {
        assertEquals(826.0, Whoop4SkinTemp.ANCHOR_RAW, 0.001)
        assertEquals(33.0, Whoop4SkinTemp.ANCHOR_CELSIUS, 0.001)
        assertEquals(0.05, Whoop4SkinTemp.PROVISIONAL_SLOPE_C_PER_RAW, 0.001)
    }

    // ── BatterySample (protocol) ───────────────────────────────────────────

    @Test fun batterySample_defaultFields() {
        val b = BatterySample(ts = 1000, soc = 85.0, mv = 3900)
        assertEquals(1000, b.ts)
        assertEquals(85.0, b.soc!!, 0.001)
        assertEquals(3900, b.mv)
        assertNull(b.charging)
    }

    @Test fun batterySample_charging() {
        val b = BatterySample(ts = 1000, soc = 90.0, mv = 4000, charging = true)
        assertEquals(true, b.charging)
    }
}
