package com.noop.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for protocol enums — [PacketType], [MetadataType], [EventNumber],
 * [CommandNumber], [DeviceFamily], [PuffinPacketType].
 */
class EnumsTest {

    // ── PacketType ─────────────────────────────────────────────────────────

    @Test fun packetType_fromRaw_knownValues() {
        assertEquals(PacketType.COMMAND, PacketType.fromRaw(35))
        assertEquals(PacketType.COMMAND_RESPONSE, PacketType.fromRaw(36))
        assertEquals(PacketType.REALTIME_DATA, PacketType.fromRaw(40))
        assertEquals(PacketType.EVENT, PacketType.fromRaw(48))
        assertEquals(PacketType.METADATA, PacketType.fromRaw(49))
        assertEquals(PacketType.CONSOLE_LOGS, PacketType.fromRaw(50))
    }

    @Test fun packetType_fromRaw_unknownReturnsNull() {
        assertNull(PacketType.fromRaw(0))
        assertNull(PacketType.fromRaw(255))
        assertNull(PacketType.fromRaw(99))
    }

    @Test fun packetType_rawValues() {
        assertEquals(35, PacketType.COMMAND.rawValue)
        assertEquals(40, PacketType.REALTIME_DATA.rawValue)
        assertEquals(48, PacketType.EVENT.rawValue)
        assertEquals(49, PacketType.METADATA.rawValue)
    }

    // ── MetadataType ───────────────────────────────────────────────────────

    @Test fun metadataType_fromRaw() {
        assertEquals(MetadataType.HISTORY_START, MetadataType.fromRaw(1))
        assertEquals(MetadataType.HISTORY_END, MetadataType.fromRaw(2))
        assertEquals(MetadataType.HISTORY_COMPLETE, MetadataType.fromRaw(3))
        assertNull(MetadataType.fromRaw(0))
        assertNull(MetadataType.fromRaw(99))
    }

    // ── EventNumber ────────────────────────────────────────────────────────

    @Test fun eventNumber_fromRaw_knownValues() {
        assertEquals(EventNumber.BATTERY_LEVEL, EventNumber.fromRaw(3))
        assertEquals(EventNumber.CHARGING_ON, EventNumber.fromRaw(7))
        assertEquals(EventNumber.CHARGING_OFF, EventNumber.fromRaw(8))
        assertEquals(EventNumber.WRIST_ON, EventNumber.fromRaw(9))
        assertEquals(EventNumber.WRIST_OFF, EventNumber.fromRaw(10))
        assertEquals(EventNumber.DOUBLE_TAP, EventNumber.fromRaw(14))
        assertEquals(EventNumber.HAPTICS_FIRED, EventNumber.fromRaw(60))
    }

    @Test fun eventNumber_fromRaw_unknownReturnsNull() {
        assertNull(EventNumber.fromRaw(0))
        assertNull(EventNumber.fromRaw(255))
    }

    // ── CommandNumber ──────────────────────────────────────────────────────

    @Test fun commandNumber_fromRaw_knownValues() {
        assertEquals(CommandNumber.TOGGLE_REALTIME_HR, CommandNumber.fromRaw(3))
        assertEquals(CommandNumber.SET_CLOCK, CommandNumber.fromRaw(10))
        assertEquals(CommandNumber.GET_CLOCK, CommandNumber.fromRaw(11))
        assertEquals(CommandNumber.GET_BATTERY_LEVEL, CommandNumber.fromRaw(26))
        assertEquals(CommandNumber.GET_DATA_RANGE, CommandNumber.fromRaw(34))
        assertEquals(CommandNumber.RUN_HAPTICS_PATTERN, CommandNumber.fromRaw(79))
        assertEquals(CommandNumber.GET_HELLO, CommandNumber.fromRaw(145))
    }

    @Test fun commandNumber_fromRaw_unknownReturnsNull() {
        assertNull(CommandNumber.fromRaw(0))
        assertNull(CommandNumber.fromRaw(255))
    }

    @Test fun commandNumber_destructiveCommandsExcluded() {
        // Destructive commands should NOT be in the enum
        assertNull(CommandNumber.fromRaw(1))   // reboot
        assertNull(CommandNumber.fromRaw(2))   // firmware load
    }

    // ── DeviceFamily ───────────────────────────────────────────────────────

    @Test fun deviceFamily_headerCRCKind() {
        assertEquals(HeaderCRCKind.CRC8, DeviceFamily.WHOOP4.headerCRCKind)
        assertEquals(HeaderCRCKind.CRC16_MODBUS, DeviceFamily.WHOOP5.headerCRCKind)
    }

    @Test fun deviceFamily_serviceUuidStrings() {
        assertTrue(DeviceFamily.WHOOP4.serviceUuidString.isNotEmpty())
        assertTrue(DeviceFamily.WHOOP5.serviceUuidString.isNotEmpty())
        assertNotEquals(DeviceFamily.WHOOP4.serviceUuidString, DeviceFamily.WHOOP5.serviceUuidString)
    }

    @Test fun deviceFamily_characteristicUuidStrings() {
        assertEquals(4, DeviceFamily.WHOOP4.characteristicUuidStrings.size)
        assertTrue(DeviceFamily.WHOOP5.characteristicUuidStrings.size >= 4)
    }

    @Test fun deviceFamily_commandCharacteristicUuidString() {
        assertEquals(
            DeviceFamily.WHOOP4.characteristicUuidStrings[0],
            DeviceFamily.WHOOP4.commandCharacteristicUuidString,
        )
        assertEquals(
            DeviceFamily.WHOOP5.characteristicUuidStrings[0],
            DeviceFamily.WHOOP5.commandCharacteristicUuidString,
        )
    }

    @Test fun deviceFamily_clientHello() {
        // WHOOP4 has no CLIENT_HELLO
        assertNull(DeviceFamily.WHOOP4.clientHello)
        // WHOOP5 has a fixed CLIENT_HELLO
        val hello = DeviceFamily.WHOOP5.clientHello
        assertNotNull(hello)
        assertEquals(0xAA.toByte(), hello!![0])
    }

    @Test fun deviceFamily_clientHello_copyNotShared() {
        // Each call to clientHello returns a fresh copy
        val a = DeviceFamily.WHOOP5.clientHello!!
        val b = DeviceFamily.WHOOP5.clientHello!!
        assertNotSame(a, b)
        assertArrayEquals(a, b)
    }

    // ── PuffinPacketType ───────────────────────────────────────────────────

    @Test fun puffinPacketType_constants() {
        assertEquals(38, PuffinPacketType.PUFFIN_COMMAND_RESPONSE)
        assertEquals(56, PuffinPacketType.PUFFIN_METADATA)
    }
}
