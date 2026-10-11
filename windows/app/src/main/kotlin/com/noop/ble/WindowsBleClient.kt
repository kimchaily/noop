package com.noop.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

// MARK: - WindowsBleClient interface (UI-facing contract)
//
// The simplified BLE contract the DesktopAppViewModel consumes. Unlike [BleClient] (the
// detailed transport interface used by [WhoopModelManager]), this interface exposes only
// the non-suspend, MAC-free operations the UI layer needs: connect/disconnect toggles,
// realtime HR stream ref-counting, and a [state] flow alias for [BleClient.liveState].
//
// The real Windows BLE implementation (another agent is creating it via WinRT GATT) will
// implement both [BleClient] and [WindowsBleClient]. Until then, [NoopBleClient] serves
// as the stub for UI development, and [WindowsBleClientStub] serves as the stub for the
// transport layer.

/**
 * UI-facing BLE contract. Provides the subset of BLE operations the [DesktopAppViewModel]
 * and screens need: a [state] flow (alias for the live connection/biometric snapshot),
 * connect/disconnect toggles, battery refresh, and realtime HR stream ref-counting.
 *
 * All methods are non-suspend fire-and-forget — the UI calls them from button clicks and
 * DisposableEffect blocks. The implementation is responsible for launching its own
 * coroutines internally.
 *
 * The companion object holds the authoritative GATT UUIDs, timers, and constants shared
 * with [WhoopModel] and [WhoopModelManager].
 */
interface WindowsBleClient {

    /** Live connection + biometric state. The UI observes this as its single source of truth. */
    val state: StateFlow<LiveState>

    /** Connect to the active/pinned strap. Fire-and-forget; updates [state] on success/failure. */
    fun connect()

    /** Disconnect the current strap (keeps the bond). Fire-and-forget. */
    fun disconnect()

    /** Release the strap: disconnect + clear all targeting. Fire-and-forget. */
    fun releaseStrap()

    /** Request the strap's current battery level (publishes into [state]). */
    fun getBattery()

    /** Increment the realtime HR stream ref-count (screens call on enter composition). */
    fun requestRealtimeHr()

    /** Decrement the realtime HR stream ref-count (screens call on leave composition). */
    fun releaseRealtimeHr()

    companion object {
        /**
         * Cap on the in-app strap-log ring buffer (for the diagnostics export). Matches the
         * Android `LOG_BUFFER_MAX` and the Swift `LiveState.maxLogLines`.
         */
        const val LOG_BUFFER_MAX = 5000

        /** Fallback device id when the registry has no active device yet. */
        const val DEFAULT_DEVICE_ID = "my-whoop"

        // MARK: GATT UUIDs (authoritative, from Android WhoopBleClient.kt / BLEManager.swift).

        /** WHOOP 4.0 primary service. */
        val WHOOP4_SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")

        /** WHOOP 4.0 command write — takes SET_CLOCK, GET_BATTERY_LEVEL, SEND_HISTORICAL_DATA, etc. */
        val CMD_WRITE_CHAR: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")

        /** WHOOP 4.0 command notifications — responses to command writes. */
        val CMD_NOTIFY_CHAR: UUID = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6")

        /** WHOOP 4.0 event notifications — WRIST_ON, WRIST_OFF, DOUBLE_TAP, BATTERY_LEVEL, ... */
        val EVENT_NOTIFY_CHAR: UUID = UUID.fromString("61080004-8d6d-82b8-614a-1c8cb0f8dcc6")

        /** WHOOP 4.0 data notifications — fragmented historical offload (type-0x2F) records. */
        val DATA_NOTIFY_CHAR: UUID = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6")

        // MARK: WHOOP 5.0 / MG ("puffin") service + characteristics (EXPERIMENTAL).

        /** WHOOP 5.0 / MG primary service. */
        val WHOOP5_SERVICE: UUID = UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")

        /** WHOOP 5.0 / MG command-write char — takes the static CLIENT_HELLO. */
        val WHOOP5_CMD_WRITE_CHAR: UUID = UUID.fromString("fd4b0002-cce1-4033-93ce-002d5875f58a")

        /** WHOOP 5.0 / MG notify chars — realtime HR rides these as REALTIME_DATA frames. */
        val WHOOP5_NOTIFY_CHARS: List<UUID> = listOf(
            UUID.fromString("fd4b0003-cce1-4033-93ce-002d5875f58a"),
            UUID.fromString("fd4b0004-cce1-4033-93ce-002d5875f58a"),
            UUID.fromString("fd4b0005-cce1-4033-93ce-002d5875f58a"),
            UUID.fromString("fd4b0007-cce1-4033-93ce-002d5875f58a"),
        )

        // MARK: Standard BLE profiles. HR + R-R works UNBONDED; battery is a plain %.

        /** Standard Heart Rate service (0x180D). */
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

        /** Heart Rate Measurement characteristic (0x2A37) — notifications carry BPM + R-R. */
        val HEART_RATE_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        /** Standard Battery service (0x180F). */
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        /** Battery Level characteristic (0x2A19) — first byte = percent (5/MG) or stub 100 (4.0). */
        val BATTERY_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** Client Characteristic Configuration Descriptor — written to enable notifications. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // MARK: Timers (ported from Android, same constants).

        /** Give up a scan after this long with no strap found. */
        const val SCAN_TIMEOUT_MS = 20_000L

        /** ATT MTU to request on connect (the official app requests 247; default 23 caps at 20 bytes). */
        const val GATT_MTU = 247

        /** Bonded-handshake watchdog: if no genuine bond lands within this of service discovery, bounce. */
        const val BOND_WATCHDOG_MS = 7_000L

        /** Historical offload interval while connected+bonded. 900s = 15 min (matches WHOOP). */
        const val BACKFILL_INTERVAL_MS = 900_000L

        /** Idle watchdog: end the offload session if no frame arrives for this long. */
        const val BACKFILL_IDLE_TIMEOUT_MS = 60_000L
    }
}

// MARK: - BleClient interface (detailed transport contract)
//
// The full BLE transport interface used by [WhoopModelManager] for scan/connect/sync
// orchestration. The real implementation will implement both [BleClient] and
// [WindowsBleClient]; [WindowsBleClientStub] implements only [BleClient].

/**
 * Contract for the BLE transport operations the desktop app needs.
 *
 * The Android port has a single concrete `WhoopBleClient` wired directly to
 * `android.bluetooth.BluetoothGatt`. The desktop port cannot use the Android BLE stack, so
 * this interface decouples the contract from the implementation. Today the only implementor
 * is [WindowsBleClientStub]; a future implementor will call into the Windows BLE stack
 * (WinRT `Windows.Devices.Bluetooth` via PowerShell/JNA) to drive a real strap.
 */
interface BleClient {
    /** Live connection + biometric state. The UI observes this as its single source of truth. */
    val liveState: StateFlow<LiveState>

    /** Historical-data sync (offload) progress, or [SyncProgress.IDLE] when no session is active. */
    val syncProgress: StateFlow<SyncProgress>

    /**
     * Scan for BLE devices advertising a WHOOP service UUID. Returns when the scan completes
     * (timeout or stopped). The caller picks a device from the result list and passes its MAC
     * to [connect].
     */
    suspend fun scan(timeoutMs: Long = 20_000L): List<ScanResult>

    /**
     * Connect to the strap at [mac]. This scans for the specific MAC, opens a GATT connection,
     * discovers services, performs the WHOOP bond, and runs the connect handshake.
     */
    suspend fun connect(mac: String)

    /** Disconnect the current strap and tear down the GATT link. No-op if not connected. */
    suspend fun disconnect()

    /**
     * Release the strap: disconnect, clear all targeting, and publish [LiveState.released].
     */
    suspend fun releaseStrap()

    /** Start a historical-data offload session (SEND_HISTORICAL_DATA -> type-0x2F frames). */
    fun startSync()

    /** Stop the current offload session (if any). Safe to call when not syncing. */
    fun stopSync()
}

// MARK: - NoopBleClient (UI stub)
//
// A no-op [WindowsBleClient] for UI development and the factory default. Every method is
// a no-op; [state] publishes an empty [LiveState]. Swap for a real implementation once
// the BLE layer is wired in.

/**
 * No-op [WindowsBleClient] for UI development. The [DesktopAppViewModel.create] factory
 * uses this as the default BLE client so the entire UI can be developed and tested
 * end-to-end before the real BLE backend lands.
 */
class NoopBleClient : WindowsBleClient {
    override val state: StateFlow<LiveState> = MutableStateFlow(LiveState()).asStateFlow()
    override fun connect() {}
    override fun disconnect() {}
    override fun releaseStrap() {}
    override fun getBattery() {}
    override fun requestRealtimeHr() {}
    override fun releaseRealtimeHr() {}
}

// MARK: - WindowsBleClientStub (transport stub)
//
// The stub [BleClient] implementation. Compiles and runs on a plain JVM with no BLE
// hardware. Used by [WhoopModelManager] for transport-layer testing.
//
// ----------------------------------------------------------------------------------------------
// HOW TO REPLACE THE STUB WITH A REAL WINDOWS BLE IMPLEMENTATION
// ----------------------------------------------------------------------------------------------
//
// The desktop runs on Kotlin/JVM, which cannot call WinRT APIs directly. Three viable paths:
//
//  Plan A - PowerShell + WinRT (recommended for first cut):
//    Spawn a PowerShell process via `ProcessBuilder` that loads the WinRT type accelerators
//    and calls `Windows.Devices.Bluetooth.BluetoothLEDevice.FromBluetoothAddressAsync`,
//    `GattSession`, `GattCharacteristic.WriteValueAsync`, and
//    `GattCharacteristic.ValueChanged` (notifications). Pipe GATT notification bytes back to
//    the JVM over stdout (one Base64 line per notification). The JVM side feeds them into the
//    existing `Reassembler` -> `Framing.parseFrame` -> frame router.
//
//  Plan B - JNA over bluetoothapis.dll:
//    Use JNA to call the Win32 BluetoothAPIs (BluetoothFindFirstDevice/BluetoothFindNextDevice
//    for scanning, BluetoothSetServiceState + GATT operations via BluetoothGATTGetServices /
//    BluetoothGATTWriteValue / BluetoothGATTRegisterEventForCharacteristic).
//
//  Plan C - this stub (current):
//    Compiles and runs with no BLE hardware. The UI shows "BLE not implemented - stub mode".
//
//  Regardless of plan, the implementation should also implement [WindowsBleClient] so it
//  can be passed to [DesktopAppViewModel]. The companion-object UUIDs on [WindowsBleClient]
//  are the authoritative reference for GATT service/characteristic discovery.

/**
 * Stub [BleClient] implementation. Every method publishes honest state into
 * [liveState] / [syncProgress] so the transport layer can be developed and tested
 * before the real BLE backend lands.
 */
class WindowsBleClientStub : BleClient {

    // MARK: Published state

    private val _liveState = MutableStateFlow(LiveState())
    override val liveState: StateFlow<LiveState> = _liveState.asStateFlow()

    private val _syncProgress = MutableStateFlow(SyncProgress.IDLE)
    override val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    /** The MAC of the strap currently connected (or null). */
    private var connectedMac: String? = null

    /** Whether a sync session is running. */
    private var syncing = false

    // MARK: BleClient implementation (stub)

    override suspend fun scan(timeoutMs: Long): List<ScanResult> {
        _liveState.update { it.copy(scanning = true, statusNote = "Scanning for WHOOP straps...") }
        delay(minOf(timeoutMs, 500L))
        _liveState.update { it.copy(scanning = false, statusNote = "BLE not implemented - stub mode. No devices found.") }
        return emptyList()
    }

    override suspend fun connect(mac: String) {
        _liveState.update {
            it.copy(
                connected = false,
                scanning = false,
                statusNote = "BLE not implemented - stub mode. Cannot connect to $mac.",
            )
        }
    }

    override suspend fun disconnect() {
        connectedMac = null
        syncing = false
        _liveState.update { LiveState.disconnected(it) }
        _syncProgress.value = SyncProgress.IDLE
    }

    override suspend fun releaseStrap() {
        connectedMac = null
        syncing = false
        _liveState.update { LiveState.released(it) }
        _syncProgress.value = SyncProgress.IDLE
    }

    override fun startSync() {
        if (syncing) return
        syncing = true
        _syncProgress.value = SyncProgress.starting(
            phase = "offloading",
            message = "BLE not implemented - stub mode. Sync requested but no strap connected.",
        )
        _liveState.update { it.copy(backfilling = true) }
    }

    override fun stopSync() {
        syncing = false
        _syncProgress.value = SyncProgress.IDLE
        _liveState.update { it.copy(backfilling = false) }
    }
}
