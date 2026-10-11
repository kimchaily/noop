package com.noop.ble

/**
 * Snapshot of a historical-data sync (offload) session's progress.
 *
 * The WHOOP strap's history offload protocol has no upfront "total records" field, so a
 * precise percent is unknowable from the wire (the Android port surfaces an honest chunk
 * count instead). This class carries both the honest signals ([processed], [phase],
 * [message]) and an optional [total] for phases where the strap DOES report a bound (e.g.
 * GET_DATA_RANGE returns oldest/newest, so the UI can show a span estimate).
 *
 * Desktop counterpart of the Android `LiveState.backfilling` / `syncChunksThisSession`
 * fields, lifted into a dedicated progress object so [WindowsBleClient.syncProgress] can
 * expose it as its own [StateFlow] — the desktop UI has no foreground-service notification
 * to spam, so per-chunk updates are fine here.
 *
 *  - [isRunning]  true while an offload session is active (connect handshake → HISTORY_COMPLETE)
 *  - [phase]      human-readable phase label: "connecting", "handshake", "offloading",
 *                 "analyzing", "idle", "error"
 *  - [processed]  chunks/records acked so far this session
 *  - [total]      estimated total when known, or null (the strap doesn't report it)
 *  - [message]    free-form status detail ("History complete — 142 records", "Waiting for strap…")
 */
data class SyncProgress(
    val isRunning: Boolean = false,
    val phase: String = "idle",
    val processed: Int = 0,
    val total: Int? = null,
    val message: String? = null,
) {
    companion object {
        /** Sentinel for a session that hasn't started. */
        val IDLE = SyncProgress()

        /** Convenience: a progress at 0% with the given phase + message. */
        fun starting(phase: String, message: String? = null): SyncProgress =
            SyncProgress(isRunning = true, phase = phase, processed = 0, total = null, message = message)
    }
}
