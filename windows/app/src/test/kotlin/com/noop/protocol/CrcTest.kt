package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [Crc] — CRC8, CRC32, and CRC16-Modbus checksums.
 *
 * Verified against known test vectors from the Swift reference (FramingTests.swift)
 * and standard CRC implementations.
 */
class CrcTest {

    // ── CRC-8 (poly 0x07) ──────────────────────────────────────────────────

    @Test fun crc8_emptyArray() {
        assertEquals(0, Crc.crc8(ByteArray(0)))
    }

    @Test fun crc8_singleByte() {
        // CRC-8 of [0x00] = 0x00
        assertEquals(0x00, Crc.crc8(byteArrayOf(0x00)))
    }

    @Test fun crc8_knownVector() {
        // CRC-8/0x07 of "123456789" (ASCII) = 0xF4
        val data = "123456789".toByteArray()
        assertEquals(0xF4, Crc.crc8(data))
    }

    @Test fun crc8_rangeSlice() {
        // Ranged CRC: only checksum bytes 1..3 of a frame
        val data = byteArrayOf(0xAA.toByte(), 0x07, 0x00, 0x55, 0xFF.toByte())
        val fullRange = Crc.crc8(data)
        val slice = Crc.crc8(data, 1, 3)
        val justSlice = Crc.crc8(byteArrayOf(0x07, 0x00))
        assertEquals(justSlice, slice)
        // The slice should differ from the full range
        assert(fullRange != slice)
    }

    @Test fun crc8_whoop4LengthHeader() {
        // WHOOP 4.0 frame: length = 7 (0x07, 0x00) → CRC8 should match header byte
        val lenBytes = byteArrayOf(0x07, 0x00)
        val crc = Crc.crc8(lenBytes)
        // Build a valid WHOOP4 frame and verify
        val frame = byteArrayOf(0xAA.toByte(), 0x07, 0x00, crc.toByte())
        val computed = Crc.crc8(frame, 1, 3)
        assertEquals(crc, computed)
    }

    // ── CRC-32 (zlib, reflected, poly 0xEDB88320) ─────────────────────────

    @Test fun crc32_emptyArray() {
        // CRC-32 of empty = 0x00000000
        assertEquals(0L, Crc.crc32(ByteArray(0)))
    }

    @Test fun crc32_knownVector() {
        // CRC-32 of "123456789" (ASCII) = 0xCBF43926
        val data = "123456789".toByteArray()
        assertEquals(0xCBF43926L, Crc.crc32(data))
    }

    @Test fun crc32_singleByteZero() {
        assertEquals(0xD202EF8DL, Crc.crc32(byteArrayOf(0x00)))
    }

    @Test fun crc32_rangeSlice() {
        val data = "Hello, World!".toByteArray()
        val full = Crc.crc32(data)
        val slice = Crc.crc32(data, 7, data.size) // "World!"
        val justSlice = Crc.crc32("World!".toByteArray())
        assertEquals(justSlice, slice)
        assert(full != slice)
    }

    @Test fun crc32_whoop4Payload() {
        // Build a simple inner record, compute CRC32, verify round-trip
        val inner = byteArrayOf(35, 0, 3, 0) // type=COMMAND, seq=0, cmd=TOGGLE_REALTIME_HR
        val crc = Crc.crc32(inner)
        // Append CRC32 LE
        val frame = ByteArray(inner.size + 4)
        System.arraycopy(inner, 0, frame, 0, inner.size)
        frame[inner.size] = (crc and 0xFFL).toByte()
        frame[inner.size + 1] = ((crc ushr 8) and 0xFFL).toByte()
        frame[inner.size + 2] = ((crc ushr 16) and 0xFFL).toByte()
        frame[inner.size + 3] = ((crc ushr 24) and 0xFFL).toByte()
        // Verify: CRC32 of inner matches the stored value
        val verify = Crc.crc32(frame, 0, inner.size)
        assertEquals(crc, verify)
    }

    // ── CRC16-Modbus (poly 0xA001, init 0xFFFF, reflected) ────────────────

    @Test fun crc16Modbus_emptyArray() {
        // CRC16-Modbus of empty = 0xFFFF
        assertEquals(0xFFFF, Crc.crc16Modbus(ByteArray(0)))
    }

    @Test fun crc16Modbus_knownVector() {
        // CRC16-Modbus of "123456789" (ASCII) = 0x4B37
        val data = "123456789".toByteArray()
        assertEquals(0x4B37, Crc.crc16Modbus(data))
    }

    @Test fun crc16Modbus_rangeSlice() {
        val data = byteArrayOf(0xAA.toByte(), 0x01, 0x08, 0x00, 0x00, 0x01)
        val full = Crc.crc16Modbus(data)
        val slice = Crc.crc16Modbus(data, 0, 6)
        assertEquals(full, slice)
    }

    @Test fun crc16Modbus_whoop5Header() {
        // WHOOP 5.0 6-byte header: SOF + format + declLen LE + header bytes
        val header = byteArrayOf(
            0xAA.toByte(), 0x01, 0x08, 0x00, 0x00, 0x01,
        )
        val crc = Crc.crc16Modbus(header)
        // Build frame with CRC16 LE and verify round-trip
        val frame = ByteArray(8)
        System.arraycopy(header, 0, frame, 0, 6)
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc ushr 8) and 0xFF).toByte()
        val verify = Crc.crc16Modbus(frame, 0, 6)
        assertEquals(crc, verify)
    }
}
