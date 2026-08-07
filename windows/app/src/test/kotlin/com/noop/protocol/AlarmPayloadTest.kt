package com.noop.protocol

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Unit tests for [AlarmPayload] — WHOOP 5.0/MG wake-alarm payload encoder.
 */
class AlarmPayloadTest {

    @Test fun build_structure() {
        val wakeMs = 1_700_000_000_000L // fixed epoch millis
        val payload = AlarmPayload.build(wakeMs, alarmId = 1)
        assertEquals(20, payload.size)
        // REVISION_4
        assertEquals(0x04.toByte(), payload[0])
        // alarmId
        assertEquals(1.toByte(), payload[1])
    }

    @Test fun build_epochSecondsCorrect() {
        val wakeMs = 1_700_000_000_000L
        val payload = AlarmPayload.build(wakeMs, alarmId = 1)
        val seconds = wakeMs / 1000L
        // u32 LE at offset 2..6
        val decoded = (payload[2].toLong() and 0xFF) or
            ((payload[3].toLong() and 0xFF) shl 8) or
            ((payload[4].toLong() and 0xFF) shl 16) or
            ((payload[5].toLong() and 0xFF) shl 24)
        assertEquals(seconds, decoded)
    }

    @Test fun build_subsecondsCorrect() {
        val wakeMs = 1_700_000_500_000L // 500 ms subsecond
        val payload = AlarmPayload.build(wakeMs, alarmId = 1)
        val expectedSub = ((wakeMs % 1000L) * 32768L) / 1000L
        val decoded = (payload[6].toLong() and 0xFF) or
            ((payload[7].toLong() and 0xFF) shl 8)
        assertEquals(expectedSub, decoded)
    }

    @Test fun build_waveformEffectsAtOffset8() {
        val payload = AlarmPayload.build(1_700_000_000_000L)
        // First waveform effect byte = 47
        assertEquals(47.toByte(), payload[8])
        // Second = 152 (unsigned comparison — 152 > 127 so signed byte is -104)
        assertEquals(152, payload[9].toInt() and 0xFF)
        // Rest of waveform = 0
        for (i in 10..15) assertEquals(0.toByte(), payload[i])
    }

    @Test fun build_loopControlAndDuration() {
        val payload = AlarmPayload.build(1_700_000_000_000L)
        // loopControl LE = 0
        assertEquals(0x00.toByte(), payload[16])
        assertEquals(0x00.toByte(), payload[17])
        // overallLoop = 7
        assertEquals(7.toByte(), payload[18])
        // duration = 30
        assertEquals(30.toByte(), payload[19])
    }

    @Test fun build_alarmIdPropagated() {
        val payload = AlarmPayload.build(1_700_000_000_000L, alarmId = 5)
        assertEquals(5.toByte(), payload[1])
    }

    @Test fun disableRev2_correctBytes() {
        val payload = AlarmPayload.disableRev2()
        assertEquals(2, payload.size)
        assertEquals(0x02.toByte(), payload[0])
        assertEquals(0xFF.toByte(), payload[1])
    }

    @Test fun nextWakeEpochMs_futureToday() {
        // 2023-11-15 08:00 UTC → next 14:30 is same day
        val zone = ZoneId.of("UTC")
        val nowMs = 1_700_035_200_000L // 2023-11-15T08:00:00Z
        val wakeMs = AlarmPayload.nextWakeEpochMs(14, 30, nowMs, zone)
        // Should be 2023-11-15T14:30:00Z = 1700006400 + 14*3600 + 30*60 = 1700058600
        val expectedSecs = 1_700_058_600L
        assertEquals(expectedSecs * 1000L, wakeMs)
    }

    @Test fun nextWakeEpochMs_pastTodayRollsToTomorrow() {
        // 2023-11-15 15:00 UTC → 14:30 already passed → tomorrow
        val zone = ZoneId.of("UTC")
        val nowMs = 1_700_038_600_000L // 2023-11-15T15:00:00Z (approx)
        val wakeMs = AlarmPayload.nextWakeEpochMs(14, 30, nowMs, zone)
        // Should be 2023-11-16T14:30:00Z
        assertTrue(wakeMs > nowMs)
    }
}
