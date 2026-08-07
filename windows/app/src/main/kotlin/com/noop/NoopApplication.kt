package com.noop

import com.noop.analytics.IntelligenceEngine
import com.noop.ble.HistoricalDataCallback
import com.noop.ble.NoopBleClient
import com.noop.ble.RealWindowsBleClient
import com.noop.ble.SyncCompleteCallback
import com.noop.ble.WindowsBleClient
import com.noop.data.DesktopDatabase
import com.noop.data.DeviceRegistry
import com.noop.data.WhoopRepository
import com.noop.protocol.DeviceFamily
import com.noop.protocol.extractHistoricalStreams
import com.noop.ui.AppearancePrefs
import com.noop.ui.ChartStylePrefs
import com.noop.ui.DesktopAppViewModel
import com.noop.ui.NoopPrefs
import com.noop.ui.ThemePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// MARK: - NoopApplication — process-wide application singleton (the desktop twin of Android's
//         `com.noop.NoopApplication : Application`)
//
// On Android the Application class is the natural owner of process-wide singletons: the
// Room-backed [WhoopRepository], the [DeviceRegistry], and the BLE client all live there so a
// foreground service can keep streaming when the Activity is backgrounded. On the JVM (Compose
// Desktop) there is no Application class — the `main()` entry point is the analogue — so this
// singleton object plays the same role: it owns the repository, the device registry, and the
// process-wide [DesktopAppViewModel], and it provides a pair of lifecycle methods ([init] /
// [shutdown]) that [MainKt] calls at window open / close.
//
// The single [DesktopDatabase] connection is held by [DesktopDatabase.get] (a process singleton);
// both the repository and the device registry share that one connection.

/**
 * Process-wide application state for the NOOP Windows desktop port.
 *
 * Holds the [WhoopRepository] (over the singleton [DesktopDatabase]) and the
 * [DesktopAppViewModel] so every screen shares the same store + BLE client. Call [init] once from
 * [MainKt] before the Compose window composes, and [shutdown] on window close to release the
 * database file handles.
 *
 * Thread-safety: [init] and [shutdown] are guarded by a monitor so they are safe to call from the
 * Swing EDT (the normal case) without racing a second caller.
 */
object NoopApplication {

    @Volatile
    private var initialized: Boolean = false

    /** IO scope for async DB operations (historical data persistence). Cancelled in [shutdown]. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The process-wide SQLite-backed repository. Set by [init]; null before / after. */
    @Volatile
    var repository: WhoopRepository? = null
        private set

    /** The process-wide device registry over the same [DesktopDatabase]. Set by [init]. */
    @Volatile
    var deviceRegistry: DeviceRegistry? = null
        private set

    /** The process-wide ViewModel shared by every screen. Set by [init]; null before / after. */
    @Volatile
    var viewModel: DesktopAppViewModel? = null
        private set

    /** The absolute path of the database file this process was initialised against. */
    @Volatile
    var dbPath: String? = null
        private set

    /**
     * Initialise the process-wide state: open (or create) the SQLite database at [dbPath], build
     * the [WhoopRepository] + [DeviceRegistry] over it, create the [DesktopAppViewModel], and load
     * the persisted appearance / theme / chart-style preferences so [NoopTheme] resolves the
     * correct palette before the first composition (no flash).
     *
     * Idempotent: a second call with the SAME [dbPath] is a no-op; a call with a DIFFERENT path
     * after a prior init is rejected (the singleton DB connection cannot be repointed at runtime —
     * call [shutdown] first).
     */
    fun init(dbPath: String) {
        synchronized(this) {
            if (initialized) {
                check(this.dbPath == dbPath) {
                    "NoopApplication already initialised against ${this.dbPath}; call shutdown() first to reinit."
                }
                return
            }

            // 1. Open / create the database and run any pending migrations (process singleton).
            //    DesktopDatabase.get caches the instance, so the repository and registry below
            //    share the ONE JDBC connection.
            val db = DesktopDatabase.get(dbPath)

            // 2. Build the repository + device registry over the singleton database.
            val repo = WhoopRepository(db)
            val registry = DeviceRegistry(db)

            // 3. Create the process-wide ViewModel. It owns the BLE client —
            //    [RealWindowsBleClient] spawns a C# subprocess that talks to the WinRT
            //    Bluetooth APIs (which Kotlin/JVM cannot call directly). The bridge
            //    handles scan/connect/bond/handshake/HR-stream/history-offload and
            //    reports back over JSON stdin/stdout. Falls back to [NoopBleClient]
            //    if the bridge DLL is not deployed.
            val ble: WindowsBleClient = runCatching { RealWindowsBleClient() }
                .getOrElse { NoopBleClient() }

            // 3a. Wire the historical-data callback so sync'd records are decoded + persisted.
            //     The C# bridge forwards raw frames as Base64 in `historicalDataBatch` events;
            //     the callback feeds them to extractHistoricalStreams() and inserts the resulting
            //     StreamBatch into the repository.
            val deviceId = runCatching { runBlocking { registry.activeDeviceId() } }
                .getOrNull() ?: WhoopRepository.WHOOP_SOURCE
            (ble as? RealWindowsBleClient)?.setHistoricalDataCallback(
                HistoricalDataCallback { frames ->
                    ioScope.launch {
                        runCatching {
                            val batch = extractHistoricalStreams(
                                rawFrames = frames,
                                deviceClockRef = 0,
                                wallClockRef = 0,
                                family = DeviceFamily.WHOOP4,
                            )
                            if (!batch.isEmpty) {
                                repo.insert(batch, deviceId)
                            }
                        }
                    }
                }
            )

            // 3b. Wire the sync-complete callback so post-sync analysis runs automatically
            //     after a history offload finishes (mirrors Android WhoopBleClient.exitBackfilling).
            (ble as? RealWindowsBleClient)?.setSyncCompleteCallback(
                SyncCompleteCallback { _ ->
                    ioScope.launch {
                        runCatching {
                            IntelligenceEngine.analyzeRecent(
                                repo = repo,
                                importedDeviceId = deviceId,
                            )
                        }
                    }
                }
            )

            val vm = DesktopAppViewModel(repository = repo, ble = ble, deviceRegistry = registry)

            // 4. Load the persisted preference surface so NoopTheme resolves the correct palette +
            //    typeface before the first composition (no flash). NoopPrefs is backed by
            //    java.util.prefs.Preferences (user-scoped, process-wide).
            NoopPrefs.of()
            AppearancePrefs.load()
            ThemePrefs.load()
            ChartStylePrefs.load()

            // 5. Publish.
            this.dbPath = dbPath
            this.repository = repo
            this.deviceRegistry = registry
            this.viewModel = vm
            this.initialized = true
        }
    }

    /**
     * Release all process-wide resources: drops the ViewModel + repository + registry references and
     * closes the singleton [DesktopDatabase] connection (which checkpoints the WAL and releases the
     * file handles). Safe to call even when [init] was never called or [shutdown] already ran.
     *
     * After [shutdown], a subsequent [init] (possibly against a different [dbPath], e.g. after a
     * backup-restore file swap) rebuilds against whatever file is on disk.
     */
    fun shutdown() {
        synchronized(this) {
            // Cancel the ViewModel's coroutine scope (stops live-state collection, sync
            // coroutines, etc.) before dropping the reference.
            viewModel?.close()
            // Shut down the BLE bridge subprocess if it's running.
            (viewModel?.ble as? RealWindowsBleClient)?.shutdown()
            // Cancel the IO scope for historical-data persistence.
            ioScope.cancel()
            // Drop the references so nothing can touch the DB mid-close.
            viewModel = null
            deviceRegistry = null
            repository = null
            dbPath = null
            initialized = false
            // Close the singleton DB connection (checkpoints WAL + releases file handles).
            DesktopDatabase.close()
        }
    }

    /** True once [init] has run and the repository / viewModel are available. */
    val isReady: Boolean get() = initialized

    /**
     * Reinitialise the application after a backup-restore file swap.
     *
     * Calls [shutdown] (closes the DB connection, cancels the ViewModel's coroutine scope,
     * drops all references) then [init] against the SAME [dbPath] — which now points at the
     * swapped-in backup file. The next `DesktopDatabase.get` call rebuilds the schema against
     * the new file, and a fresh [DesktopAppViewModel] is published so every screen re-collects
     * from the new database.
     *
     * After this call returns, [viewModel] is a NEW instance — the caller MUST discard any
     * stale ViewModel reference and read [viewModel] again.
     */
    fun reinit(dbPath: String) {
        shutdown()
        init(dbPath)
    }
}
