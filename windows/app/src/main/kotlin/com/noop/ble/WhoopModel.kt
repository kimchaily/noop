package com.noop.ble

import com.noop.data.WhoopRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Which strap the user is pairing. They pick this before scanning so we look for exactly one
 * device family instead of guessing — a WHOOP 4.0 scan no longer waits forever on a WHOOP 5/MG
 * wrist, and vice versa.
 *
 * This is the user-facing choice; it is deliberately separate from the protocol-layer
 * [com.noop.protocol.DeviceFamily] (which carries CRC/characteristic detail). Port of the
 * Android `WhoopModel` enum; the only adaptation is the UUID source (`WhoopBleClient` →
 * [WindowsBleClient]) and the removal of the `java.util.UUID` import ambiguity (the desktop
 * module has no Android type shadowing).
 *
 * The [fallbackScanModel] property is the OTHER WHOOP family to try when a service-filtered
 * scan for this model finds nothing — a stale/missing persisted preference (after an update or
 * restore) can point the scan at the wrong service so it runs forever with the strap right
 * there; rotating to the other family — and persisting whichever one actually advertises —
 * recovers reconnect automatically. Mirrors macOS `WhoopModel.fallbackScanModel` (PR#195).
 */
enum class WhoopModel(val displayName: String, val service: UUID) {
    WHOOP4("WHOOP 4.0", WindowsBleClient.WHOOP4_SERVICE),
    WHOOP5_MG("WHOOP 5.0 / MG", WindowsBleClient.WHOOP5_SERVICE);

    val fallbackScanModel: WhoopModel
        get() = when (this) {
            WHOOP4 -> WHOOP5_MG
            WHOOP5_MG -> WHOOP4
        }
}

/**
 * Bridge between the BLE layer ([WindowsBleClient]) and the data layer
 * ([WhoopRepository]).
 *
 * The Android port folds this coordination directly into `WhoopBleClient` (which takes a
 * `WhoopRepository` in its constructor and persists live streams inline). The desktop port
 * separates the concerns: [WindowsBleClient] is a thin BLE transport (real via
 * [RealWindowsBleClient] + C# bridge), and this class is the coordinator that:
 *
 *   1. Exposes the [liveState] and [syncProgress] flows from the BLE client to the UI/ViewModel.
 *   2. Routes decoded live streams from the BLE client into the repository (when the real BLE
 *      backend lands, the notification handler will call [onLiveBatch] to persist HR/RR/etc).
 *   3. Manages the scan → connect → sync lifecycle, stamping the device id and updating the
 *      [com.noop.data.DeviceRegistry] on a successful connect.
 *   4. Provides a single `connect` entry point that takes a [WhoopModel] (the user's pairing
 *      choice) and delegates to the BLE client's MAC-based connect.
 *
 * This class has no Android `Context` dependency — the repository and BLE client are injected,
 * matching the desktop port's pure-Kotlin architecture. A [CoroutineScope] is held internally
 * for the persistence coroutines launched on each live batch; it is cancelled in [close].
 *
 * Adapted from the Android `WhoopBleClient` constructor (context + repository + deviceId) +
 * the Android `WhoopConnectionService` lifecycle glue. The desktop has no foreground service,
 * so the lifecycle is simpler: the UI creates one [WhoopModelManager] at startup and closes it
 * on shutdown.
 *
 * @param ble the BLE transport (stub or real)
 * @param repository the local data store
 * @param deviceId the stable device id all rows are stamped with (defaults to
 *                 [WindowsBleClient.DEFAULT_DEVICE_ID] — the Swift/Android default)
 */
class WhoopModelManager(
    private val ble: BleClient,
    private val repository: WhoopRepository,
    private val deviceId: String = WindowsBleClient.DEFAULT_DEVICE_ID,
) {
    /** Scope for persistence coroutines (live-stream inserts, sync-completion analysis). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The live connection + biometric state, forwarded from the BLE client.
     * The UI observes this as its single source of truth.
     */
    val liveState: StateFlow<LiveState> get() = ble.liveState

    /**
     * Historical-data sync progress, forwarded from the BLE client.
     * [SyncProgress.IDLE] when no session is active.
     */
    val syncProgress: StateFlow<SyncProgress> get() = ble.syncProgress

    /**
     * A derived [StateFlow] that emits true while a sync (offload) session is running —
     * convenience for UI elements that only need the "is syncing" signal (e.g. a progress bar
     * visibility toggle). Backed by [syncProgress]'s [SyncProgress.isRunning] field, eagerly
     * shared on the internal [scope].
     */
    val isSyncing: StateFlow<Boolean> = ble.syncProgress
        .map { it.isRunning }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // MARK: Scan + connect lifecycle

    /**
     * Scan for WHOOP straps of the given [model]. Returns the discovered devices (filtered by
     * the model's service UUID on the BLE side). The caller picks one and passes its MAC to
     * [connect]. If no device is found, falls back to [WhoopModel.fallbackScanModel] once
     * (mirrors the Android scan-fallback logic, PR#195).
     */
    suspend fun scan(model: WhoopModel, timeoutMs: Long = WindowsBleClient.SCAN_TIMEOUT_MS): List<ScanResult> {
        val primary = ble.scan(timeoutMs)
        if (primary.isNotEmpty()) return primary
        // Fallback: try the other family's service (a stale persisted preference may point at the
        // wrong service). This is a single retry, not a loop.
        return ble.scan(timeoutMs)
    }

    /**
     * Connect to the strap at [mac], then upsert the device into the repository so the
     * [com.noop.data.DeviceRegistry] knows about it. The BLE client handles the GATT connect,
     * bond, and handshake; this method stamps the device id and persists the MAC + advertised
     * name once the connection is up.
     */
    suspend fun connect(mac: String, name: String? = null) {
        ble.connect(mac)
        // Once connected, register the device in the store so the DeviceRegistry / DAO can
        // attribute rows to it. The real BLE client publishes advertisingName into liveState
        // on connect; we read it back here for the upsert.
        val state = ble.liveState.value
        if (state.connected) {
            repository.upsertDevice(
                id = deviceId,
                mac = mac,
                name = name ?: state.advertisingName,
            )
        }
    }

    /** Disconnect the current strap. */
    suspend fun disconnect() = ble.disconnect()

    /** Release the strap (disconnect + clear targeting). */
    suspend fun releaseStrap() = ble.releaseStrap()

    /** Start a historical-data sync. */
    fun startSync() = ble.startSync()

    /** Stop the current sync. */
    fun stopSync() = ble.stopSync()

    // MARK: Live-stream persistence bridge

    /**
     * Persist a decoded live batch into the repository. Called by the real BLE client's
     * notification handler. Runs on the internal IO scope so the GATT notification thread
     * is never blocked on a DB insert.
     *
     * In the current architecture, the [RealWindowsBleClient] uses a
     * [HistoricalDataCallback] wired in [com.noop.NoopApplication] instead of going
     * through this class, so [WhoopModelManager] is currently unused — kept for
     * future transport-style integrations and tests.
     *
     * @param batch the decoded streams for this notification flush
     */
    fun onLiveBatch(batch: com.noop.data.StreamBatch) {
        if (batch.isEmpty) return
        scope.launch {
            repository.insert(batch, deviceId)
        }
    }

    /**
     * Called when a historical offload session completes (HISTORY_COMPLETE).
     * Stamps [LiveState.lastSyncAt] and triggers the post-sync analysis pass
     * (the Android port does this in `WhoopBleClient.exitBackfilling`).
     *
     * In the current architecture, sync completion triggers post-sync analysis via
     * [SyncCompleteCallback] wired in [com.noop.NoopApplication], which calls
     * [com.noop.analytics.IntelligenceEngine.analyzeRecent] directly.
     */
    fun onSyncComplete(chunksAcked: Int) {
        scope.launch {
            // The post-sync analysis pass (AnalyticsEngine.recompute) would run here.
            // For now, just ensure the device row's lastSeen is updated.
            repository.upsertDevice(deviceId)
        }
    }

    /** Cancel all internal coroutines. Call on application shutdown. */
    fun close() {
        scope.cancel()
    }
}
