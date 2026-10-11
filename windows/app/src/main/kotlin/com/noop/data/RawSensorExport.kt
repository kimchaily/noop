package com.noop.data

import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Export raw sensor data from the local SQLite database to a CSV file.
 *
 * Desktop port of Android `RawSensorExport`. Produces a long-format CSV (one row per
 * sample) with 18 columns, matching the Android export format exactly so downstream
 * analysis tools work identically.
 *
 * Supported streams: hr, rr, gravity, steps, ppghr, spo2, skintemp, resp.
 *
 * The export runs entirely on-device — no data leaves the user's machine. The caller
 * provides the destination [File]; the method writes the CSV and returns a per-stream
 * sample count.
 */
object RawSensorExport {

    /** CSV header row — matches Android RawSensorExport. */
    private const val HEADER =
        "unix_s,iso_utc,stream,hr_bpm,rr_ms,grav_x,grav_y,grav_z,step_counter," +
            "ppg_bpm,ppg_conf,spo2_red,spo2_ir,skintemp_raw,resp_raw," +
            "band_sleep_state,event_kind,event_payload"

    /** Per-stream sample limit (matches Android default). */
    private const val DEFAULT_LIMIT = 200_000

    /**
     * Export raw sensor data for [deviceId] between [from] and [to] (Unix seconds)
     * to [destFile]. Returns a map of stream name → sample count.
     *
     * The caller must ensure [WhoopRepository] is initialised and accessible.
     */
    fun export(
        destFile: File,
        repo: WhoopRepository,
        deviceId: String,
        from: Long,
        to: Long,
        limit: Int = DEFAULT_LIMIT,
    ): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        FileWriter(destFile).use { writer ->
            writer.write(HEADER)
            writer.write("\n")

            val rows = mutableListOf<LineRow>()

            // HR
            val hr = kotlinx.coroutines.runBlocking { repo.rawHrSamples(deviceId, from, to, limit) }
            if (hr.isNotEmpty()) {
                counts["hr"] = hr.size
                hr.forEach { s -> rows.add(LineRow(s.ts) { "hr,${s.bpm},${",".repeat(15)}" }) }
            }

            // RR
            val rr = kotlinx.coroutines.runBlocking { repo.rrIntervals(deviceId, from, to, limit) }
            if (rr.isNotEmpty()) {
                counts["rr"] = rr.size
                rr.forEach { s -> rows.add(LineRow(s.ts) { ",rr,,${s.rrMs},${",".repeat(13)}" }) }
            }

            // Gravity
            val grav = kotlinx.coroutines.runBlocking { repo.gravitySamples(deviceId, from, to, limit) }
            if (grav.isNotEmpty()) {
                counts["gravity"] = grav.size
                grav.forEach { s -> rows.add(LineRow(s.ts) { ",,gravity,,,${s.x},${s.y},${s.z},${",".repeat(10)}" }) }
            }

            // Steps
            val steps = kotlinx.coroutines.runBlocking { repo.stepSamples(deviceId, from, to, limit) }
            if (steps.isNotEmpty()) {
                counts["steps"] = steps.size
                steps.forEach { s -> rows.add(LineRow(s.ts) { ",,steps,,,,,,${s.counter},${",".repeat(9)}" }) }
            }

            // PPG HR
            val ppg = kotlinx.coroutines.runBlocking { repo.ppgHrSamples(deviceId, from, to, limit) }
            if (ppg.isNotEmpty()) {
                counts["ppghr"] = ppg.size
                ppg.forEach { s -> rows.add(LineRow(s.ts) { ",,ppghr,,,,,,,${s.bpm},${s.conf},${",".repeat(7)}" }) }
            }

            // SpO2
            val spo2 = kotlinx.coroutines.runBlocking { repo.spo2Samples(deviceId, from, to, limit) }
            if (spo2.isNotEmpty()) {
                counts["spo2"] = spo2.size
                spo2.forEach { s -> rows.add(LineRow(s.ts) { ",,spo2,,,,,,,,${s.red},${s.ir},${",".repeat(5)}" }) }
            }

            // Skin temp
            val skin = kotlinx.coroutines.runBlocking { repo.skinTempSamples(deviceId, from, to, limit) }
            if (skin.isNotEmpty()) {
                counts["skintemp"] = skin.size
                skin.forEach { s -> rows.add(LineRow(s.ts) { ",,skintemp,,,,,,,,,,${s.raw},${",".repeat(3)}" }) }
            }

            // Respiration
            val resp = kotlinx.coroutines.runBlocking { repo.respSamples(deviceId, from, to, limit) }
            if (resp.isNotEmpty()) {
                counts["resp"] = resp.size
                resp.forEach { s -> rows.add(LineRow(s.ts) { ",,resp,,,,,,,,,,,${s.raw},${",".repeat(2)}" }) }
            }

            // Sort all rows by timestamp and write.
            rows.sortBy { it.ts }
            val isoFmt = DateTimeFormatter.ISO_INSTANT
            for (row in rows) {
                val iso = isoFmt.format(Instant.ofEpochSecond(row.ts))
                writer.write("${row.ts},$iso,")
                writer.write(row.line())
                writer.write("\n")
            }
        }
        return counts
    }

    /** A timestamped CSV line, lazily formatted. */
    private class LineRow(val ts: Long, val line: () -> String)
}
