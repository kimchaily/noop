package com.noop.ui

import com.noop.analytics.IntelligenceEngine
import com.noop.ble.LiveState
import com.noop.ble.NoopBleClient
import com.noop.ble.WindowsBleClient
import com.noop.data.DailyMetric
import com.noop.data.DeviceRegistry
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The single app-wide view model for the desktop port. Holds the BLE client and the
 * repository, re-publishes the BLE [LiveState], maintains a spike-filtered/smoothed
 * BPM for the big read-outs, and exposes device management + analysis-trigger surface.
 *
 * Adapted from the Android [AppViewModel]:
 *  - `AndroidViewModel` / `viewModelScope` → plain class + `CoroutineScope(SupervisorJob() + Dispatchers.Main)`
 *  - `Application` / `NoopApplication` → constructor-injected [WhoopRepository] + [WindowsBleClient]
 *  - `HealthConnectClient` / `WidgetSnapshot` / `SmartAlarmScheduler` / notifications → removed (not on JVM)
 *  - `SharedPreferences` reads → [NoopPrefs] (JVM `java.util.prefs`)
 *  - `SourceCoordinator` → removed (single-source desktop); device ops go straight to the registry
 *
 * The core public surface (live, bpm, today, recentDays, pairedDevices, runAnalysis)
 * mirrors the Android twin so screens port with minimal call-site changes.
 */
class DesktopAppViewModel(
    val repository: WhoopRepository,
    val ble: WindowsBleClient,
    val deviceRegistry: DeviceRegistry,
) {

    /** The scope that replaces `viewModelScope`; cancelled in [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The active strap source id (raw streams + imported history live under this). */
    val activeStrapId: String get() = deviceId

    /** The active device id, resolved once from the registry (falls back to the legacy "my-whoop"). */
    private val deviceId: String = runCatching {
        kotlinx.coroutines.runBlocking { deviceRegistry.activeDeviceId() }
    }.getOrNull() ?: WhoopRepository.WHOOP_SOURCE

    // MARK: - Live state + smoothed BPM

    /** Live connection + biometric snapshot, surfaced straight from the BLE client. */
    val live: StateFlow<LiveState> = ble.state

    /** Spike-filtered, smoothed heart rate for the hero number. Null until data arrives. */
    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm.asStateFlow()

    private val hrWindow = ArrayDeque<Int>()
    private val hrWindowSize = 5

    // MARK: - Today's cached metrics

    private val _today = MutableStateFlow<DailyMetric?>(null)
    val today: StateFlow<DailyMetric?> = _today.asStateFlow()

    /** Recent daily metrics (newest last), backing the Today grid + trends. */
    val recentDays: StateFlow<List<DailyMetric>> =
        repository.recentDaysMergedFlow(deviceId)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // MARK: - Data refresh status

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastAnalysisDay = MutableStateFlow<String?>(null)
    val lastAnalysisDay: StateFlow<String?> = _lastAnalysisDay.asStateFlow()

    // MARK: - Active device name

    private val _activeDeviceName = MutableStateFlow<String?>(null)
    val activeDeviceName: StateFlow<String?> = _activeDeviceName.asStateFlow()

    // MARK: - Workouts (cached for the Workouts screen)

    private val _workouts = MutableStateFlow<List<WorkoutRow>>(emptyList())
    val workouts: StateFlow<List<WorkoutRow>> = _workouts.asStateFlow()

    // MARK: - Sleep sessions (cached for the Sleep screen)

    private val _sleepSessions = MutableStateFlow<List<SleepSession>>(emptyList())
    val sleepSessions: StateFlow<List<SleepSession>> = _sleepSessions.asStateFlow()

    init {
        // Smooth HR from each LiveState emission; clear on disconnect.
        scope.launch {
            ble.state.collect { state ->
                state.heartRate?.let { ingestHr(it) }
                if (state.heartRate == null && state.rr.isEmpty()) resetSmoothing()
            }
        }
        // Recompute today's row whenever cached days change.
        scope.launch {
            recentDays.collect { days ->
                val logicalKey = logicalDayKeyNow()
                val localKey = java.time.LocalDate.now().toString()
                _today.value = resolveTodayRow(days, logicalKey, localKey)
            }
        }
        // Resolve the active band's display name at launch.
        refreshActiveDeviceName()
        // Load initial workouts + sleep.
        refreshWorkouts()
        refreshSleepSessions()
    }

    // MARK: - HR smoothing

    private fun ingestHr(bpm: Int) {
        hrWindow.addLast(bpm)
        while (hrWindow.size > hrWindowSize) hrWindow.removeFirst()
        val sorted = hrWindow.sorted()
        _bpm.value = sorted[sorted.size / 2]
    }

    private fun resetSmoothing() {
        hrWindow.clear()
        _bpm.value = null
    }

    // MARK: - Device management

    /** All paired devices (oldest first), read fresh. */
    suspend fun pairedDevices(): List<PairedDeviceRow> = deviceRegistry.all()

    /** Add (or update) a paired device. */
    suspend fun addPairedDevice(row: PairedDeviceRow) = deviceRegistry.add(row)

    /** Make [id] the single active device, then refresh the display name. */
    suspend fun setActiveDevice(id: String) {
        deviceRegistry.setActive(id)
        refreshActiveDeviceName()
    }

    /** Archive (remove) a device — keeps its row + samples (invariant I4). */
    suspend fun archivePairedDevice(id: String) {
        deviceRegistry.archive(id)
    }

    /** Rename a device (blank clears the nickname → falls back to brand+model). */
    suspend fun renamePairedDevice(id: String, nickname: String?) =
        deviceRegistry.rename(id, nickname)

    /** Permanently delete all of a device's recorded data (its registry row is kept). */
    suspend fun deletePairedDeviceData(id: String) = deviceRegistry.deleteDeviceData(id)

    /** Re-read the active device row and republish its display name. */
    fun refreshActiveDeviceName() {
        scope.launch {
            val all = runCatching { deviceRegistry.all() }.getOrDefault(emptyList())
            val active = all.firstOrNull { it.status == DeviceStatus.active.name }
            _activeDeviceName.value = active?.let { displayName(it) }
        }
    }

    // MARK: - Data refresh

    /** Reload the workout list from the repository for the active device. */
    fun refreshWorkouts() {
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            val lo = now - 365L * 86_400L
            _workouts.value = runCatching {
                repository.workouts(deviceId, lo, now, 5_000)
            }.getOrDefault(emptyList())
        }
    }

    /** Reload the sleep-session list from the repository for the active device. */
    fun refreshSleepSessions() {
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            val lo = now - 365L * 86_400L
            _sleepSessions.value = runCatching {
                repository.sleepSessionsMerged(deviceId, lo, now, 5_000)
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Trigger the on-device analytics engine for the given day (or recent days if null).
     * The desktop port delegates to [IntelligenceEngine.analyzeRecent] which computes
     * recovery / strain / sleep from the cached raw streams and upserts the resulting
     * [DailyMetric]. Sets [refreshing] while running so screens can show a spinner.
     */
    fun runAnalysis(day: String? = null) {
        scope.launch {
            _refreshing.value = true
            try {
                val targetDay = day ?: logicalDayKeyNow()
                // Run the intelligence engine over recent days. This reads raw streams
                // from the repository, runs AnalyticsEngine.analyzeDay (sleep staging,
                // recovery scoring, strain scoring, workout detection), and persists
                // the resulting DailyMetric + sleep sessions back to the repository.
                runCatching {
                    IntelligenceEngine.analyzeRecent(
                        repo = repository,
                        importedDeviceId = deviceId,
                    )
                }.onFailure { err ->
                    err.printStackTrace()
                }
                // Refresh the caches so the UI picks up the newly computed metrics.
                refreshWorkouts()
                refreshSleepSessions()
                _lastAnalysisDay.value = targetDay
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Trigger a full data refresh (workouts, sleep, analysis). */
    fun refreshAll() {
        refreshWorkouts()
        refreshSleepSessions()
        runAnalysis()
    }

    // MARK: - BLE pass-throughs

    /** Connect to the active strap. */
    fun connect() = ble.connect()

    /** Disconnect from the current strap (keeps the bond). */
    fun disconnect() = ble.disconnect()

    /** Request the strap's current battery level. */
    fun getBattery() = ble.getBattery()

    /** Increment the realtime HR stream ref-count. */
    fun requestRealtimeHr() = ble.requestRealtimeHr()

    /** Decrement the realtime HR stream ref-count. */
    fun releaseRealtimeHr() = ble.releaseRealtimeHr()

    // MARK: - Lifecycle

    /** Cancel all coroutines — call when the window/application is closing. */
    fun close() {
        scope.cancel()
    }

    // MARK: - Helpers

    /** Resolve the display name for a paired device: nickname, else collapsed brand+model. */
    private fun displayName(row: PairedDeviceRow): String =
        row.nickname?.takeIf { it.isNotBlank() }
            ?: "${row.brand} ${row.model}".trim()

    companion object {
        /**
         * Factory: create a [DesktopAppViewModel] backed by the SQLite database at [dbPath],
         * a [DeviceRegistry], and a [NoopBleClient] (swap for a real [WindowsBleClient]
         * once the BLE layer is wired in).
         */
        fun create(dbPath: String): DesktopAppViewModel {
            val repo = WhoopRepository.from(dbPath)
            val registry = DeviceRegistry(com.noop.data.DesktopDatabase.get(dbPath))
            val ble = NoopBleClient()
            return DesktopAppViewModel(repository = repo, ble = ble, deviceRegistry = registry)
        }

        /**
         * Factory with an explicit BLE client (use once the real [WindowsBleClient]
         * implementation is available).
         */
        fun create(dbPath: String, ble: WindowsBleClient): DesktopAppViewModel {
            val repo = WhoopRepository.from(dbPath)
            val registry = DeviceRegistry(com.noop.data.DesktopDatabase.get(dbPath))
            return DesktopAppViewModel(repository = repo, ble = ble, deviceRegistry = registry)
        }
    }
}
