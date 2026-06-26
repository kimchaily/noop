package com.noop.testcentre

import android.content.Context
import android.os.Build
import com.noop.BuildConfig
import com.noop.CrashCapture
import com.noop.ble.redactStrapLogPii

/**
 * Twin of the Swift TestBundleAssembler: gathers the bundle files, re-runs the redaction pass over EVERY
 * file, applies the 20 MB cap, and hands the entries to LogExport.exportBundle.
 *
 * The CRITICAL fix (spec section 5.3): today only the WhoopBleClient.log() sink scrubs, so a serial
 * embedded in raw-capture console text would ship unredacted. We re-run the file-scope redactStrapLogPii
 * over every entry here, the single scrub point, and stamp meta.redaction = "v2".
 */
object TestBundleAssembler {

    const val REDACTION_VERSION = "v2"

    /**
     * Re-run the redaction sink over every entry. Text entries are decoded UTF-8, scrubbed via the same
     * redactStrapLogPii used by the live log sink, and re-encoded. raw-capture is where the embedded
     * serials live; report.txt and meta.json have no PII shapes so they pass through unchanged.
     */
    fun redactEntries(entries: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> =
        entries.map { (name, data) ->
            val scrubbed = redactStrapLogPii(String(data))
            name to scrubbed.toByteArray()
        }

    /**
     * Hard cap the bundle at [capBytes] (20 MB default, under GitHub's 25 MB; spec section 5.4). The
     * strap-log tail is already bounded, so only raw-capture can exceed. Keep the MOST-RECENT tail of
     * raw-capture.jsonl and trim from the front. Returns the capped entries plus whether truncation
     * happened, which the caller writes to meta.truncated.
     */
    fun capEntries(
        entries: List<Pair<String, ByteArray>>,
        capBytes: Int = 20 * 1024 * 1024,
    ): Pair<List<Pair<String, ByteArray>>, Boolean> {
        val total = entries.sumOf { it.second.size }
        if (total <= capBytes) return entries to false
        val rawName = "raw-capture.jsonl"
        val nonRaw = entries.filter { it.first != rawName }.sumOf { it.second.size }
        val budget = maxOf(0, capBytes - nonRaw)
        var truncated = false
        val capped = entries.map { (name, data) ->
            if (name == rawName && data.size > budget) {
                truncated = true
                name to data.copyOfRange(data.size - budget, data.size)  // keep the tail
            } else {
                name to data
            }
        }
        return capped to truncated
    }

    // assemble (the entrypoint behind the Report button, the Group D integration seam) ----------------

    /**
     * Gather the report files for [profile], redact EVERY file, cap the bundle, then build and append
     * meta.json (carrying the truncated flag from the cap) and redact-pass it too. Returns the final,
     * already-redacted + already-capped entries ready for LogExport.exportBundle.
     *
     * Group B left this entrypoint to Group D (the Test Centre IA) on purpose: it is the one place that
     * reaches into the live app (the strap log, the crash capture, the diagnostics, TestCentre) to gather
     * the actual bytes, so it lives with the screen that binds the Report button. The pure primitives
     * (redactEntries, capEntries) stay where Group B shipped them; this just composes them in order.
     *
     * [logText] is the live strap-log tail (vm.ble.exportLogText()) so the assembler stays off the BLE
     * client and is testable; the header and last crash are added here to match the strap-log file.
     */
    fun assemble(context: Context, profile: TestDomain, logText: String): List<Pair<String, ByteArray>> {
        // 1. report.txt: header (app + Android diagnostics) + the strap-log body, the same shape the
        //    strap-log share writes. Already scrubbed by the log() sink; the redactEntries pass re-scrubs.
        val header = buildString {
            appendLine("NOOP strap log")
            appendLine("App:     ${BuildConfig.VERSION_NAME} (${BuildConfig.TIER})")
            for (line in AndroidDiagnostics.summaryLines(context)) appendLine(line)
            appendLine("-".repeat(40))
        }
        val body = logText.ifBlank {
            "(strap log is empty, connect to your strap, reproduce the issue, then report again)"
        }
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries.add("report.txt" to (header + "\n" + body).toByteArray())

        // last-crash.txt: only if a crash was captured (degrade gracefully, never fabricate).
        CrashCapture.lastCrash(context)?.let { crash ->
            entries.add("last-crash.txt" to crash.toByteArray())
        }

        // 2. Redact every gathered file, then cap (only raw-capture can exceed; report.txt is bounded).
        val redacted = redactEntries(entries)
        val (capped, truncated) = capEntries(redacted)
        val out = ArrayList(capped)

        // 3. meta.json: the machine-readable tie. Answers + startedAt come off the single TestCentre
        //    surface. Storage is left zeroed in Phase 1 (the DB-size probe is a later wire-up); we never
        //    fabricate a number we cannot read. The Android build is unsigned-flavour, channel "GitHub".
        val tc = TestCentre.from(context)
        val started = tc.startedAt(profile)?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                .format(java.util.Date(it * 1000L))
        }
        val meta = TestBundleMeta(
            schema = 1,
            appVersion = BuildConfig.VERSION_NAME,
            platform = "Android",
            osVersion = Build.VERSION.RELEASE ?: "?",
            strapModel = null,
            source = listOf("Live Bluetooth"),
            testProfile = profile.id,
            profileStartedAt = started,
            questionnaire = tc.answers(profile),
            build = TestBundleMeta.Build(channel = "GitHub", signed = false),
            storage = TestBundleMeta.Storage(dbBytes = 0, rows = emptyMap(), rawCaptureBytes = 0),
            redaction = REDACTION_VERSION,
            truncated = truncated,
        )
        // meta.json has no PII shapes, but route it through the same sink so the whole bundle has passed
        // one scrub point (5.3).
        out += redactEntries(listOf("meta.json" to meta.encoded().toByteArray()))
        return out
    }
}
