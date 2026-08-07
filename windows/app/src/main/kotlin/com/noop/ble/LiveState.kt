package com.noop.ble

/**
 * Immutable snapshot of the live connection + biometric state.
 *
 * Direct port of the Android `LiveState` (com.noop.ble.WhoopBleClient.kt) and the Swift
 * `LiveState` (Strand/BLE/LiveState.swift), reduced to the fields the Windows desktop UI
 * consumes. The Android version used an `@Published` ObservableObject with closures in
 * Swift; the desktop port surfaces the most-recent physical input through [lastEvent] and
 * exposes wrist-wear through [worn]; the ViewModel reacts to changes in the [LiveState]
 * flow published by [WindowsBleClient].
 *
 * Where the Android version depended on `android.content.Context` (for SharedPreferences
 * reads like `lastSyncAt`), the desktop version moves that responsibility to the caller —
 * [WindowsBleClient] seeds these fields from [com.noop.ui.NoopPrefs] at construction.
 *
 *  - [connected]        GATT connection is up (CBPeripheral didConnect)
 *  - [bonded]           one confirmed write to the command char has been ACKed (the WHOOP "bond")
 *  - [encryptedBond]    true ONLY when the link reached a GENUINE encrypted bond — the 5/MG
 *                       CLIENT_HELLO ack, the WHOOP4 confirmed-write bond, or a strap-reported
 *                       BLE_BONDED event. NOT set by the live-HR shortcut that flips [bonded]
 *                       true when HR streams over the unbonded standard profile on a 5/MG — so
 *                       [bonded] can be true while this is false ("Live HR, not fully paired").
 *  - [streamingLiveHR]  true ONLY when a non-WHOOP live source is actively streaming live HR.
 *                       Deliberately separate from [bonded], which carries WHOOP encrypted-bond
 *                       + buzz semantics (it gates haptics) and must NOT be set by the non-WHOOP
 *                       path. The owning source sets it true in its streaming branch and false
 *                       at every teardown.
 *  - [heartRate]        most-recent plausible BPM (30..220) from the standard 0x2A37 profile OR
 *                       the custom REALTIME_DATA frame
 *  - [rr]               most-recent R-R intervals (ms); the standard profile is the reliable source
 *  - [rrRecent]         rolling UI buffer of recent R-R intervals (capped, oldest dropped first).
 *                       The standard BLE HR notification usually carries only one or two intervals
 *                       per packet, so the Live console needs a short history to render a moving
 *                       R-R strip / rolling RMSSD. Appended (never replaced) via [withRRIntervals];
 *                       emptied by [clearedBiometrics].
 *  - [batteryPct]       battery percent — 5/MG: 0x2A19 whole %; WHOOP 4: GET_BATTERY_LEVEL
 *                       response u16/10 (the 4.0's 0x2A19 is a stub constant 100 and is ignored)
 *  - [charging]         charging flag from BATTERY_LEVEL events. Null until the strap reports.
 *  - [worn]             wrist-wear from WRIST_ON/WRIST_OFF events. Defaults true (Swift parity)
 *                       so wear-gated features work before the first event lands.
 *  - [lastEvent]        the most-recent strap EVENT string ("WRIST_ON(9)", "DOUBLE_TAP(14)", ...)
 *  - [firmwareVersion]  strap firmware version captured during the connect handshake. WHOOP 4.0
 *                       reports `fw_harvard` (a.b.c.d) via REPORT_VERSION_INFO; WHOOP 5/MG reports
 *                       `fw_version` via GET_HELLO. Shown on the Devices card. Null until the
 *                       handshake response decodes. (Android name: `strapFirmware`.)
 *  - [advertisingName]  the strap's current BLE advertising name, captured on connect. Drives the
 *                       "Rename strap" card. Null until connected.
 *  - [renameStatus]     status of the last strap-rename attempt, surfaced in Settings.
 *  - [scanning]         true while actively scanning for the strap.
 *  - [statusNote]       human-readable reason for the current state.
 *  - [whoop5Detected]   a WHOOP 5/MG strap was found but live data needs an MG secure handshake.
 *  - [backfilling]      true while a historical offload session is running.
 *  - [syncChunksThisSession]  chunks acked during the current offload session.
 *  - [lastSyncAt]       wall-clock (unix seconds) of the last offload that ran to HISTORY_COMPLETE.
 *  - [lastSyncError]    set when an offload ended abnormally.
 *  - [reconnectGuide]   set when a connect attempt fails because the strap wiped its bond.
 *  - [pairingHint]      set when a WHOOP 5/MG strap keeps refusing the encrypted bond.
 *  - [historySyncExperimental]  true when a 5/MG streams live HR but hands over no history offload.
 */
data class LiveState(
    val connected: Boolean = false,
    val bonded: Boolean = false,
    val encryptedBond: Boolean = false,
    val streamingLiveHR: Boolean = false,
    val heartRate: Int? = null,
    val rr: List<Int> = emptyList(),
    val rrRecent: List<Int> = emptyList(),
    val batteryPct: Double? = null,
    val charging: Boolean? = null,
    val worn: Boolean = true,
    val lastEvent: String? = null,
    val firmwareVersion: String? = null,
    val advertisingName: String? = null,
    val renameStatus: String? = null,
    val scanning: Boolean = false,
    val statusNote: String? = null,
    val whoop5Detected: Boolean = false,
    val backfilling: Boolean = false,
    val syncChunksThisSession: Int = 0,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
    val reconnectGuide: String? = null,
    val pairingHint: String? = null,
    val historySyncExperimental: Boolean = false,
) {
    /**
     * Set the fresh [heartRate] and return a new state. Preserves all other fields including
     * the rolling R-R buffer — the standard BLE HR notification carries both HR and R-R in the
     * same packet, but the caller routes R-R through [withRRIntervals] separately so this method
     * only touches [heartRate]. Added for the desktop port (the Android version inlined
     * `_state.update { it.copy(heartRate = bpm) }` at every call site).
     */
    fun withHeartRate(bpm: Int?): LiveState = copy(heartRate = bpm)

    /**
     * Set the fresh-packet [rr] AND append the valid intervals onto the bounded [rrRecent] rolling
     * buffer (oldest fall off first). Non-positive sentinels are dropped from the rolling buffer.
     * Port of the Android `LiveState.withRRIntervals` / macOS `LiveState.setRRIntervals`.
     */
    fun withRRIntervals(intervals: List<Int>, recentLimit: Int = 60): LiveState {
        val valid = intervals.filter { it > 0 }
        if (valid.isEmpty()) return copy(rr = intervals)
        val merged = rrRecent + valid
        val capped = if (merged.size > recentLimit) merged.takeLast(recentLimit) else merged
        return copy(rr = intervals, rrRecent = capped)
    }

    /**
     * Blank all live biometric readouts (HR + R-R + the rolling buffer) so a stale heart rate or
     * R-R strip can't outlive the link. Applied on disconnect alongside the charging/bond clears.
     * Port of the Android `LiveState.clearedBiometrics` / macOS `LiveState.clearBiometrics`.
     */
    fun clearedBiometrics(): LiveState =
        copy(heartRate = null, rr = emptyList(), rrRecent = emptyList())

    /**
     * Mark the link as bonded (and optionally encrypted) and return a new state. Added for the
     * desktop port: the Android version inlined `_state.update { it.copy(bonded = true) }` at
     * the confirmed-write ACK site; centralising it here keeps the stub and future real BLE
     * implementation in sync. Pass [encrypted] = true ONLY for a genuine encrypted bond.
     */
    fun withBonded(encrypted: Boolean = false): LiveState =
        copy(bonded = true, encryptedBond = encrypted || encryptedBond)

    companion object {
        /**
         * The LiveState published when the GATT link drops — biometrics cleared, bond/encrypted
         * flags dropped, sync stopped, firmware/charging cleared. A stale firmware version must
         * not outlive the dropped link. Port of the Android `LiveState.disconnectedLiveState`.
         */
        fun disconnected(previous: LiveState): LiveState =
            previous.clearedBiometrics().copy(
                connected = false, bonded = false, encryptedBond = false,
                backfilling = false, syncChunksThisSession = 0, charging = null,
                firmwareVersion = null,
                historySyncExperimental = false,
            )

        /**
         * The LiveState published when the user removes the device — the link fully dropped +
         * every stale live readout cleared, so a removed strap can't keep showing live HR / a
         * bond / a charging pill. Port of the Android `LiveState.releasedLiveState`.
         */
        fun released(previous: LiveState): LiveState =
            previous.clearedBiometrics().copy(
                connected = false, bonded = false, encryptedBond = false,
                charging = null, firmwareVersion = null, pairingHint = null,
                scanning = false, statusNote = null,
            )
    }
}
