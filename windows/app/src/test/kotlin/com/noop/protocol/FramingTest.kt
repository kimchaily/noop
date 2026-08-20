package com.noop.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [Framing] and [Reassembler] — frame building, validation, parsing,
 * and BLE fragment reassembly for WHOOP 4.0 and 5.0.
 */
class FramingTest {

    // ── Reassembler (WHOOP 4.0) ────────────────────────────────────────────

    @Test fun reassembler_singleCompleteFrame() {
        val frame = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val reassembler = Reassembler()
        val out = reassembler.feed(frame)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test fun reassembler_splitFrame() {
        val frame = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val reassembler = Reassembler()
        // Split at a non-SOF boundary
        val mid = frame.size / 2
        val out1 = reassembler.feed(frame.copyOfRange(0, mid))
        assertTrue(out1.isEmpty())
        val out2 = reassembler.feed(frame.copyOfRange(mid, frame.size))
        assertEquals(1, out2.size)
        assertArrayEquals(frame, out2[0])
    }

    @Test fun reassembler_multipleFrames() {
        val f1 = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val f2 = Framing.buildCommand(CommandNumber.GET_CLOCK, byteArrayOf(0x00), seq = 1)
        val reassembler = Reassembler()
        val out = reassembler.feed(f1 + f2)
        assertEquals(2, out.size)
        assertArrayEquals(f1, out[0])
        assertArrayEquals(f2, out[1])
    }

    @Test fun reassembler_leadingGarbageDiscarded() {
        val frame = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val reassembler = Reassembler()
        val out = reassembler.feed(garbage + frame)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test fun reassembler_resetClearsBuffer() {
        val frame = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val reassembler = Reassembler()
        val mid = frame.size / 2
        reassembler.feed(frame.copyOfRange(0, mid))
        reassembler.reset()
        val out = reassembler.feed(frame.copyOfRange(mid, frame.size))
        // After reset the partial fragment is gone, so the second half alone won't reassemble
        assertTrue(out.isEmpty())
    }

    @Test fun reassembler_twoFramesAcrossFeeds() {
        val f1 = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val f2 = Framing.buildCommand(CommandNumber.GET_CLOCK, byteArrayOf(0x00), seq = 1)
        val reassembler = Reassembler()
        val out1 = reassembler.feed(f1)
        assertEquals(1, out1.size)
        val out2 = reassembler.feed(f2)
        assertEquals(1, out2.size)
        assertArrayEquals(f1, out1[0])
        assertArrayEquals(f2, out2[0])
    }

    // ── buildCommand (WHOOP 4.0) ───────────────────────────────────────────

    @Test fun buildCommand_structure() {
        val frame = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        // SOF
        assertEquals(0xAA.toByte(), frame[0])
        // type = COMMAND (35)
        assertEquals(35.toByte(), frame[4])
        // seq
        assertEquals(0.toByte(), frame[5])
        // cmd = TOGGLE_REALTIME_HR (3)
        assertEquals(3.toByte(), frame[6])
        // payload
        assertEquals(0x01.toByte(), frame[7])
    }

    @Test fun buildCommand_crcValid() {
        val frame = Framing.buildCommand(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0x01), seq = 0)
        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP4)
        assertTrue(parsed.ok)
        assertEquals(true, parsed.crcOk)
        assertEquals("COMMAND", parsed.typeName) // buildCommand makes type 35 → COMMAND
    }

    @Test fun buildCommand_roundTrip() {
        val frame = Framing.buildCommand(CommandNumber.GET_CLOCK, byteArrayOf(0x00), seq = 5)
        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP4)
        assertTrue(parsed.ok)
        assertEquals(true, parsed.crcOk)
    }

    // ── parseFrame (WHOOP 4.0) ─────────────────────────────────────────────

    @Test fun parseFrame_invalidShortFrame() {
        val parsed = Framing.parseFrame(byteArrayOf(0xAA.toByte(), 0x01, 0x00), DeviceFamily.WHOOP4)
        assertFalse(parsed.ok)
        assertEquals("INVALID/FRAGMENT", parsed.typeName)
    }

    @Test fun parseFrame_wrongSof() {
        val parsed = Framing.parseFrame(ByteArray(10) { 0x00 }, DeviceFamily.WHOOP4)
        assertFalse(parsed.ok)
    }

    @Test fun parseFrame_realtimeData() {
        // Build a REALTIME_DATA frame (type 40)
        // Inner: type(40) + seq(0) + cmd(0) + timestamp(u32@6→offset 3..7 of inner) + ...
        // Actually the inner record for Whoop4 is: [type][seq][cmd][payload...]
        // REALTIME_DATA layout: timestamp@6(u32) = inner offset 2..6, heart_rate@12(u8), rr_count@13
        // inner = frame[4..length], so inner[0]=type, inner[1]=seq, inner[2]=cmd
        // timestamp is at frame[6] = inner[2..6]
        val inner = ByteArray(12)
        inner[0] = 40 // REALTIME_DATA
        inner[1] = 0  // seq
        inner[2] = 0  // cmd
        // timestamp u32 LE at frame[6] = inner[2..6]
        inner[2] = 0x78; inner[3] = 0x56; inner[4] = 0x34; inner[5] = 0x12 // 0x12345678
        // subseconds u16 at frame[10] = inner[6..8]
        inner[6] = 0x00; inner[7] = 0x00
        // heart_rate u8 at frame[12] = inner[8]
        inner[8] = 72
        // rr_count u8 at frame[13] = inner[9]
        inner[9] = 0
        // inner[10..12] unused padding

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
        assertTrue(parsed.ok)
        assertEquals(true, parsed.crcOk)
        assertEquals("REALTIME_DATA", parsed.typeName)
        assertEquals(0x12345678, parsed.parsed["timestamp"])
        assertEquals(72, parsed.parsed["heart_rate"])
        assertEquals(0, parsed.parsed["rr_count"])
    }

    // ── puffinCommandFrame (WHOOP 5.0) ─────────────────────────────────────

    @Test fun puffinCommandFrame_structure() {
        val frame = Framing.puffinCommandFrame(cmd = 19, seq = 0, payload = byteArrayOf(0x00))
        // SOF
        assertEquals(0xAA.toByte(), frame[0])
        // format byte
        assertEquals(0x01.toByte(), frame[1])
        // Minimum size: 6 header + 2 CRC16 + inner + 4 CRC32
        assertTrue(frame.size >= 12)
    }

    @Test fun puffinCommandFrame_roundTrip() {
        val frame = Framing.puffinCommandFrame(cmd = 19, seq = 0, payload = byteArrayOf(0x00))
        val parsed = Framing.parseFrame(frame, DeviceFamily.WHOOP5)
        assertTrue(parsed.ok)
        // CRC should be valid since we built it ourselves
        assertEquals(true, parsed.crcOk)
    }

    @Test fun puffinCommandFrame_padding() {
        // A 12-byte payload (inner = 3 + 12 = 15) should pad to 16
        val payload = ByteArray(12) { it.toByte() }
        val frame = Framing.puffinCommandFrame(cmd = 19, seq = 0, payload = payload)
        // declLen = padded_inner(16) + 4 = 20
        val declLen = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        assertEquals(20, declLen)
    }

    // ── DeviceFamily.CLIENT_HELLO (WHOOP 5.0) ──────────────────────────────

    @Test fun whoop5ClientHello_parses() {
        val hello = DeviceFamily.WHOOP5_CLIENT_HELLO
        val parsed = Framing.parseFrame(hello, DeviceFamily.WHOOP5)
        assertTrue(parsed.ok)
        assertEquals(true, parsed.crcOk)
    }

    @Test fun whoop5ClientHello_is16Bytes() {
        assertEquals(16, DeviceFamily.WHOOP5_CLIENT_HELLO.size)
    }
}
