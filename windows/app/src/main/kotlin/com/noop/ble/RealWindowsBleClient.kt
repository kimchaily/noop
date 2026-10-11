package com.noop.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// MARK: - RealWindowsBleClient
//
// Real BLE client for Windows desktop. Spawns a C# subprocess (WhoopBleBridge) that
// talks to the WinRT Bluetooth APIs (which Kotlin/JVM cannot call directly). The bridge
// reads JSON commands from its stdin and writes JSON events to its stdout; this class
// is the JVM-side counterpart: it sends commands and consumes the event stream.
//
// The bridge executable is expected at one of these locations (searched in order):
//   1. System property `noop.ble.bridge` (absolute path to the .dll)
//   2. Environment variable `WHOOP_BLE_BRIDGE`
//   3. Next to the app JAR: ./ble/WhoopBleBridge.dll
//   4. Under %LOCALAPPDATA%\NOOP\ble\
//   5. Under %APPDATA%\NOOP\ble\
//   6. Build output dirs (dev convenience): WhoopBleBridge/bin/{Release,Debug}/net8.0-windows10.0.22621.0/
//
// This class implements [WindowsBleClient] (the UI-facing contract). It also exposes
// transport-style methods (scan, connect, startSync, stopSync) for future [BleClient]
// integration, but does NOT formally implement [BleClient] because that interface
// declares suspend versions of disconnect/releaseStrap which conflict with the
// non-suspend versions required by [WindowsBleClient].

/**
 * Callback for receiving decoded historical-data frames from the C# bridge.
 * The Kotlin side feeds these to [com.noop.protocol.extractHistoricalStreams]
 * and persists the resulting [com.noop.data.StreamBatch] via [com.noop.data.WhoopRepository].
 */
fun interface HistoricalDataCallback {
    /**
     * @param frames Raw BLE frames (complete, CRC-verified) ready for [com.noop.protocol.extractHistoricalStreams]
     */
    fun onHistoricalDataBatch(frames: List<ByteArray>)
}

/**
 * Callback fired when a historical-data sync (offload) session completes
 * (the C# bridge emits a `syncComplete` event). The Kotlin side can trigger
 * post-sync analysis (IntelligenceEngine.analyzeRecent) at this point.
 */
fun interface SyncCompleteCallback {
    /** @param chunksAcked total records synced in this session */
    fun onSyncComplete(chunksAcked: Int)
}

/**
 * Real [WindowsBleClient] backed by a C# BLE bridge subprocess.
 *
 * The bridge process is spawned on [connect] (or [scan]) and kept alive for the lifetime
 * of the connection. All BLE events from the strap are parsed from the bridge's JSON
 * stdout stream and published into [state] / [syncProgress].
 */
class RealWindowsBleClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : WindowsBleClient {

    // ── Published state ─────────────────────────────────────────────────────
    private val _state = MutableStateFlow(LiveState())
    override val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Alias for [state], kept for compatibility with [BleClient]-shaped callers. */
    val liveState: StateFlow<LiveState> = _state.asStateFlow()

    private val _syncProgress = MutableStateFlow(SyncProgress.IDLE)
    /** Historical-data sync (offload) progress. */
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    // ── Bridge process ──────────────────────────────────────────────────────
    private var process: Process? = null
    private var writer: Writer? = null
    private var readerThread: Job? = null
    private val seq = AtomicInteger(0)
    @Volatile private var connected = false
    @Volatile private var currentAddress: String? = null
    @Volatile private var bridgeReady = false

    // HR ref-counting
    private val hrRefCount = AtomicInteger(0)

    /** Callback invoked when a batch of historical-data frames arrives from the bridge. */
    @Volatile
    private var historicalDataCallback: HistoricalDataCallback? = null
    private var syncCompleteCallback: SyncCompleteCallback? = null

    /**
     * Set the callback that receives historical-data frame batches from the bridge.
     * The callback is invoked on the IO dispatcher; it should be cheap or offload work.
     */
    fun setHistoricalDataCallback(callback: HistoricalDataCallback?) {
        this.historicalDataCallback = callback
    }

    /** Set the callback fired when a sync (offload) session completes. */
    fun setSyncCompleteCallback(callback: SyncCompleteCallback?) {
        this.syncCompleteCallback = callback
    }

    // ── WindowsBleClient (UI-facing) ───────────────────────────────────────

    override fun connect() {
        scope.launch {
            if (currentAddress == null) {
                // No pinned device — scan first, then auto-connect to the first WHOOP found
                if (!ensureBridgeRunning()) return@launch
                // Wait for the bridge process to emit its first line (ready signal)
                var readyWait = 0
                while (!bridgeReady && readyWait < 30) {
                    delay(200)
                    // If the process died, don't keep waiting
                    if (process?.isAlive == false) {
                        _state.update { it.copy(statusNote = "BLE bridge process exited unexpectedly. Check stderr output above.") }
                        return@launch
                    }
                    readyWait++
                }
                if (!bridgeReady) {
                    _state.update { it.copy(statusNote = "BLE bridge timed out — no ready signal. Check dotnet runtime / bridge DLL.") }
                    return@launch
                }
                _state.update { it.copy(scanning = true, statusNote = "Scanning for WHOOP straps…") }
                sendCommand("""{"cmd":"scan","timeout_ms":20000}""")
                // Wait for the reader thread to set currentAddress from scanResult events
                var waitCount = 0
                while (currentAddress == null && waitCount < 40) {
                    delay(500)
                    waitCount++
                }
                _state.update { it.copy(scanning = false) }
            }
            val addr = currentAddress
            if (addr == null) {
                _state.update { it.copy(statusNote = "No WHOOP device found. Make sure your strap is nearby and awake.") }
                return@launch
            }
            doConnect(addr)
        }
    }

    // WindowsBleClient.disconnect() — non-suspend, fire-and-forget.
    override fun disconnect() {
        scope.launch { doDisconnect() }
    }

    override fun releaseStrap() {
        scope.launch { doReleaseStrap() }
    }

    private suspend fun doReleaseStrap() {
        doDisconnect()
        currentAddress = null
        _state.update { LiveState.released(it) }
    }

    override fun getBattery() {
        sendCommand("""{"cmd":"getBattery"}""")
    }

    override fun requestRealtimeHr() {
        if (hrRefCount.incrementAndGet() == 1) {
            sendCommand("""{"cmd":"requestRealtimeHr"}""")
        }
    }

    override fun releaseRealtimeHr() {
        if (hrRefCount.decrementAndGet() <= 0) {
            hrRefCount.set(0)
            sendCommand("""{"cmd":"releaseRealtimeHr"}""")
        }
    }

    // ── Transport-style methods (kept for future WhoopModelManager integration) ──

    suspend fun scan(timeoutMs: Long): List<ScanResult> {
        if (!ensureBridgeRunning()) return emptyList()
        synchronized(scanResults) { scanResults.clear() }
        _state.update { it.copy(scanning = true, statusNote = "Scanning for WHOOP straps…") }
        sendCommand("""{"cmd":"scan","timeout_ms":$timeoutMs}""")
        // Wait for scanComplete event (timeout + buffer)
        delay(minOf(timeoutMs + 2000, 30_000))
        _state.update { it.copy(scanning = false) }
        // Return the devices collected by the reader thread from scanResult events.
        return synchronized(scanResults) { scanResults.toList() }
    }

    suspend fun connect(mac: String) {
        currentAddress = mac
        doConnect(mac)
    }

    fun startSync() {
        _syncProgress.value = SyncProgress.starting(phase = "offloading", message = "Requesting historical data…")
        _state.update { it.copy(backfilling = true) }
        sendCommand("""{"cmd":"startSync"}""")
    }

    fun stopSync() {
        sendCommand("""{"cmd":"stopSync"}""")
        _syncProgress.value = SyncProgress.IDLE
        _state.update { it.copy(backfilling = false) }
    }

    // ── Bridge lifecycle ───────────────────────────────────────────────────

    private fun findDefaultDevice(): String? {
        // The bridge will auto-discover on connect if no address is given
        return currentAddress
    }

    private suspend fun doConnect(address: String) {
        _state.update { it.copy(scanning = true, statusNote = "Connecting to $address…") }
        if (!ensureBridgeRunning()) return
        sendCommand("""{"cmd":"connect","address":"$address"}""")
    }

    private suspend fun doDisconnect() {
        sendCommand("""{"cmd":"disconnect"}""")
        connected = false
        _state.update { LiveState.disconnected(it) }
        _syncProgress.value = SyncProgress.IDLE
    }

    // ── Bridge process management ───────────────────────────────────────────

    private fun findBridgePath(): String? {
        // 1. System property
        System.getProperty("noop.ble.bridge")?.let { if (File(it).exists()) return it }
        // 2. Environment variable
        System.getenv("WHOOP_BLE_BRIDGE")?.let { if (File(it).exists()) return it }
        // 3. Next to the app JAR / working directory
        val localPath = File("ble/WhoopBleBridge.dll").absolutePath
        if (File(localPath).exists()) return localPath
        // 4. Under %LOCALAPPDATA%\NOOP\ble\
        val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        val appSupportPath = File(localAppData, "NOOP/ble/WhoopBleBridge.dll").absolutePath
        if (File(appSupportPath).exists()) return appSupportPath
        // 5. Under %APPDATA%\NOOP\ble\
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val roamingPath = File(appData, "NOOP/ble/WhoopBleBridge.dll").absolutePath
        if (File(roamingPath).exists()) return roamingPath
        // 6. Build output directories (development convenience — finds the DLL right after
        //    `dotnet build` without needing to run build.sh)
        val buildDirs = listOf(
            "WhoopBleBridge/bin/Release/net8.0-windows10.0.22621.0",
            "WhoopBleBridge/bin/Debug/net8.0-windows10.0.22621.0",
            "../WhoopBleBridge/bin/Release/net8.0-windows10.0.22621.0",
            "../WhoopBleBridge/bin/Debug/net8.0-windows10.0.22621.0",
        )
        for (dir in buildDirs) {
            val candidate = File(dir, "WhoopBleBridge.dll")
            if (candidate.exists()) return candidate.absolutePath
        }
        return null
    }

    @Synchronized
    private fun ensureBridgeRunning(): Boolean {
        if (process?.isAlive == true) return true

        val bridgePath = findBridgePath()
        if (bridgePath == null) {
            _state.update { it.copy(statusNote = "BLE bridge not found. Build WhoopBleBridge and deploy to ble/ or %LOCALAPPDATA%\\NOOP\\ble\\") }
            return false
        }

        val dotnetExe = findDotnet()
        if (dotnetExe == null) {
            _state.update { it.copy(statusNote = ".NET 8.0 runtime not found. Install from https://dotnet.microsoft.com/download") }
            return false
        }

        val pb = ProcessBuilder(dotnetExe, bridgePath)
        pb.redirectErrorStream(false) // keep stderr separate for diagnostics
        try {
            val proc = pb.start()
            process = proc
            writer = OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)
            connected = false
            bridgeReady = false

            // Start reader thread
            readerThread = scope.launch { readBridgeOutput(proc) }

            // Start stderr reader (for diagnostics)
            scope.launch { readBridgeError(proc) }

            return true
        } catch (e: IOException) {
            _state.update { it.copy(statusNote = "Failed to start BLE bridge: ${e.message}") }
            return false
        }
    }

    /**
     * Locate the dotnet executable. Tries PATH first, then common install locations.
     */
    private fun findDotnet(): String? {
        // 1. Try "dotnet" in PATH (ProcessBuilder searches PATH on all platforms)
        val dotnetInPath = runCatching {
            val proc = ProcessBuilder("dotnet", "--version").redirectErrorStream(true).start()
            // 5-second timeout — if dotnet exists but is slow/hanging, don't block the UI
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
            if (!exited) proc.destroyForcibly()
            exited && proc.exitValue() == 0
        }.getOrNull() == true
        if (dotnetInPath) return "dotnet"
        // 2. Common 64-bit install location
        val progFiles = File("C:\\Program Files\\dotnet\\dotnet.exe")
        if (progFiles.exists()) return progFiles.absolutePath
        // 3. Common 32-bit install location
        val progFiles86 = File("C:\\Program Files (x86)\\dotnet\\dotnet.exe")
        if (progFiles86.exists()) return progFiles86.absolutePath
        return null
    }

    private fun sendCommand(json: String) {
        val w = writer ?: return
        try {
            w.write(json)
            w.write("\n")
            w.flush()
        } catch (e: IOException) {
            // Bridge process may have died
            _state.update { it.copy(statusNote = "BLE bridge communication error: ${e.message}") }
        }
    }

    // ── Scan results collection ────────────────────────────────────────────

    private val scanResults = mutableListOf<ScanResult>()

    // ── JSON event reader ─────────────────────────────────────────────────

    private suspend fun readBridgeOutput(proc: Process) {
        val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                // Mark bridge as ready on first output line
                if (!bridgeReady) bridgeReady = true
                parseBridgeEvent(line)
            }
        } catch (e: IOException) {
            // Process ended
        }
        // Bridge exited
        bridgeReady = false
        connected = false
        _state.update { LiveState.disconnected(it).copy(statusNote = "BLE bridge exited") }
    }

    private suspend fun readBridgeError(proc: Process) {
        val reader = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                // Route stderr to the diagnostic console; do NOT overwrite statusNote
                // (which is managed by parseBridgeEvent) to avoid race conditions
                // where a diagnostic line clobbers an important status like "Connected".
                System.err.println("[bridge-stderr] $line")
            }
        } catch (e: IOException) {
            // ignore
        }
    }

    // ── JSON event parser (using org.json for robust parsing) ────────────

    private fun parseBridgeEvent(line: String) {
        val json = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return
        val event = json.optString("event")
        if (event.isEmpty()) return

        when (event) {
            "scanResult" -> {
                val name = json.optString("name", null)
                val address = json.optString("address", null) ?: return
                val rssi = json.optInt("rssi", -100)
                if (currentAddress == null) {
                    currentAddress = address
                }
                synchronized(scanResults) {
                    scanResults.add(ScanResult(deviceName = name, mac = address, rssi = rssi))
                }
                _state.update {
                    it.copy(
                        scanning = true,
                        statusNote = "Found: $name ($rssi dBm)",
                        advertisingName = name,
                    )
                }
            }

            "scanComplete" -> {
                _state.update { it.copy(scanning = false, statusNote = "Scan complete") }
            }

            "connected" -> {
                connected = true
                val name = json.optString("name", null)
                _state.update {
                    it.copy(
                        connected = true,
                        scanning = false,
                        statusNote = "Connected",
                        advertisingName = name ?: it.advertisingName,
                    )
                }
            }

            "disconnected" -> {
                connected = false
                _state.update { LiveState.disconnected(it) }
                _syncProgress.value = SyncProgress.IDLE
            }

            "bonded" -> {
                _state.update { it.withBonded(encrypted = true) }
            }

            "handshakeDone" -> {
                _state.update {
                    it.copy(
                        bonded = true,
                        encryptedBond = true,
                        statusNote = "Connected & ready",
                    )
                }
            }

            "hr" -> {
                val bpm = json.optInt("bpm", 0)
                if (bpm in 30..220) {
                    val rrs = json.optJSONArray("rr")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> arr.optInt(i, 0).takeIf { it > 0 } }
                    } ?: emptyList()
                    _state.update {
                        it.withHeartRate(bpm).withRRIntervals(rrs)
                    }
                }
            }

            "battery" -> {
                val pct = json.optDouble("pct", -1.0)
                if (pct >= 0) {
                    _state.update { it.copy(batteryPct = pct) }
                }
            }

            "strapEvent" -> {
                val name = json.optString("name", "UNKNOWN")
                val code = json.optInt("code", -1)
                val ts = json.optLong("timestamp", 0L)
                _state.update {
                    it.copy(lastEvent = "$name($code)")
                }
                when (name) {
                    "WRIST_ON" -> _state.update { it.copy(worn = true) }
                    "WRIST_OFF" -> _state.update { it.copy(worn = false) }
                    "BATTERY_LEVEL" -> {
                        val pct = json.optDouble("pct", -1.0)
                        if (pct >= 0) _state.update { it.copy(batteryPct = pct) }
                    }
                    "BLE_BONDED" -> _state.update { it.withBonded(encrypted = true) }
                }
            }

            "syncProgress" -> {
                val phase = json.optString("phase", "offloading")
                val processed = json.optInt("processed", 0)
                val msg = json.optString("message", null)
                _syncProgress.value = SyncProgress(
                    isRunning = true,
                    phase = phase,
                    processed = processed,
                    total = null,
                    message = msg,
                )
                _state.update {
                    it.copy(backfilling = true, syncChunksThisSession = processed)
                }
            }

            "historicalDataBatch" -> {
                val frames = json.optJSONArray("frames")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val b64 = arr.optString(i, "")
                        if (b64.isNotEmpty()) {
                            runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
                        } else null
                    }
                } ?: emptyList()
                if (frames.isNotEmpty()) {
                    historicalDataCallback?.onHistoricalDataBatch(frames)
                }
            }

            "syncComplete" -> {
                val processed = json.optInt("processed", 0)
                _syncProgress.value = SyncProgress(
                    isRunning = false,
                    phase = "idle",
                    processed = processed,
                    message = "Sync complete — $processed records",
                )
                _state.update {
                    it.copy(
                        backfilling = false,
                        syncChunksThisSession = processed,
                        lastSyncAt = System.currentTimeMillis() / 1000,
                    )
                }
                syncCompleteCallback?.onSyncComplete(processed)
            }

            "consoleLog" -> {
                val text = json.optString("text", "")
                if (text.isEmpty()) return
                extractFirmwareVersion(text)
            }

            "log" -> {
                val msg = json.optString("msg", "")
                if (msg.isNotEmpty()) {
                    _state.update { it.copy(statusNote = msg) }
                }
            }

            "error" -> {
                val msg = json.optString("msg", "Unknown error")
                _state.update { it.copy(statusNote = "Error: $msg") }
            }
        }
    }

    // ── Firmware version extraction ────────────────────────────────────────

    private fun extractFirmwareVersion(text: String) {
        // "BLE: HELLO: Nordic Ver: 17.2.2.0"
        val idx = text.indexOf("Nordic Ver:")
        if (idx >= 0) {
            val rest = text.substring(idx + "Nordic Ver:".length).trim()
            val ver = rest.takeWhile { it.isDigit() || it == '.' }
            if (ver.isNotEmpty() && ver.count { it == '.' } >= 2) {
                _state.update { it.copy(firmwareVersion = ver) }
            }
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    fun shutdown() {
        sendCommand("""{"cmd":"shutdown"}""")
        readerThread?.cancel()
        // Give the bridge process a moment to flush pending writes and exit cleanly
        // before forcibly terminating it (prevents WAL corruption / half-written data).
        process?.let { proc ->
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
        process = null
        writer = null
        readerThread = null
    }
}
