package com.noop.ble

import java.util.UUID

/**
 * A BLE device surfaced by a scan.
 *
 * Desktop counterpart of the Android `DiscoveredWhoop` tuple (address/name/rssi) and the
 * Swift `discoveredWhoops` tuple, widened to carry the advertised service UUIDs so the
 * caller can filter by WHOOP family without re-querying the advertisement. The macOS port
 * uses the same shape; [mac] is the BLE address (Windows uses MAC strings like
 * "AA:BB:CC:DD:EE:FF", matching the WinRT `BluetoothAddress` formatted as colon-separated
 * hex octets).
 *
 *  - [deviceName]   the advertised name (e.g. "WHOOP 4.0"), may be null if the strap didn't
 *                   broadcast one in this advertisement packet
 *  - [mac]          the BLE MAC address, upper-case colon-separated
 *  - [rssi]         received signal strength in dBm (typically -100..-30)
 *  - [serviceUuids] service UUIDs advertised by the device; used to distinguish WHOOP 4.0
 *                   (61080001-...) from WHOOP 5.0/MG (fd4b0001-...) without a follow-up
 *                   service-discovery round-trip
 */
data class ScanResult(
    val deviceName: String?,
    val mac: String,
    val rssi: Int,
    val serviceUuids: List<UUID> = emptyList(),
)
