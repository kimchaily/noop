package com.noop.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/**
 * Decoded streams to persist in one transaction. Desktop mirror of the Swift `Streams`
 * struct carrying the rows for a single flush/backfill chunk. All `ts` values are
 * wall-clock unix seconds (Long).
 *
 * The protocol/decoder layer builds one of these (deviceId stamped at insert time, not
 * stored on the per-row sample models, it is supplied to [WhoopRepository.insert]).
 */
data class StreamBatch(
    val hr: List<HrRow> = emptyList(),
    val rr: List<RrRow> = emptyList(),
    val events: List<EventEntry> = emptyList(),
    val battery: List<BatteryRow> = emptyList(),
    val spo2: List<Spo2Row> = emptyList(),
    val skinTemp: List<SkinTempRow> = emptyList(),
    val resp: List<RespRow> = emptyList(),
    val gravity: List<GravityRow> = emptyList(),
    val steps: List<StepRow> = emptyList(),
    val sleepState: List<SleepStateRow> = emptyList(),
    val ppgHr: List<PpgHrRow> = emptyList(),
    val droppedImplausibleTs: Int = 0,
) {
    val isEmpty: Boolean
        get() = hr.isEmpty() && rr.isEmpty() && events.isEmpty() && battery.isEmpty() &&
            spo2.isEmpty() && skinTemp.isEmpty() && resp.isEmpty() && gravity.isEmpty() &&
            steps.isEmpty() && sleepState.isEmpty() && ppgHr.isEmpty()
}

// Device-agnostic decoded rows (deviceId attached when inserted). Mirror Streams.swift shapes.
data class HrRow(val ts: Long, val bpm: Int)
data class RrRow(val ts: Long, val rrMs: Int)
data class EventEntry(val ts: Long, val kind: String, val payloadJSON: String)
data class BatteryRow(val ts: Long, val soc: Double?, val mv: Int?, val charging: Boolean? = null)
data class Spo2Row(val ts: Long, val red: Int, val ir: Int)
data class SkinTempRow(val ts: Long, val raw: Int)
data class StepRow(val ts: Long, val counter: Int, val activityClass: Int? = null)
data class SleepStateRow(val ts: Long, val state: Int)
data class RespRow(val ts: Long, val raw: Int)
data class GravityRow(val ts: Long, val x: Double, val y: Double, val z: Double)
data class PpgHrRow(val ts: Long, val bpm: Int, val conf: Double)

/** Count of rows ACTUALLY inserted per stream (mirrors WhoopStore.insert return tuple). */
data class InsertCounts(
    val hr: Int = 0,
    val rr: Int = 0,
    val events: Int = 0,
    val battery: Int = 0,
    val spo2: Int = 0,
    val skinTemp: Int = 0,
    val steps: Int = 0,
    val resp: Int = 0,
    val gravity: Int = 0,
)

/**
 * A compact snapshot of how much history each source holds, for the Data Sources "Freshness
 * Pipeline" card. Counts only, no per-day rows leave the read. Port of macOS RepositoryFreshness.
 */
data class DataFreshness(
    val importedDays: Int = 0,
    val computedDays: Int = 0,
    val appleDays: Int = 0,
    val importedSleeps: Int = 0,
    val computedSleeps: Int = 0,
    val earliestDay: String? = null,
    val latestDay: String? = null,
) {
    val hasAnyHistory: Boolean get() = importedDays > 0 || computedDays > 0 || appleDays > 0

    companion object {
        val EMPTY = DataFreshness()
    }
}

/**
 * #547 one-time heal predicates, kept PURE (no DB) so they are unit-testable on the JVM.
 * Bounds mirror the ingest gate exactly: a unix-second `ts` is implausible when below
 * [com.noop.protocol.MIN_PLAUSIBLE_UNIX] (2023-11) or above now + [com.noop.protocol.FUTURE_MARGIN]
 * (one day). A computed daily `day` ("yyyy-MM-dd") is implausible when it sorts AFTER the local
 * "today" key (a future-dated day) or before the floor day.
 */
object HistoryHeal {
    fun isImplausibleTs(
        ts: Long,
        nowSec: Long,
        minTs: Long = com.noop.protocol.MIN_PLAUSIBLE_UNIX,
        futureMargin: Long = com.noop.protocol.FUTURE_MARGIN,
    ): Boolean = ts < minTs || ts > nowSec + futureMargin

    fun isImplausibleDay(day: String, today: String, minDay: String): Boolean =
        day > today || day < minDay
}

/**
 * Repository over [DesktopDatabase] / [DesktopDao]. The single seam the rest of the app uses
 * to read/write the local store. Port of WhoopStore's public surface (StreamStore.swift,
 * Reads.swift, MetricsCache.swift), the desktop does NO metric computation here; daily/sleep
 * rows are an offline cache of server-computed values.
 *
 * Adapted from the Android [WhoopRepository]: WhoopDatabase -> DesktopDatabase,
 * WhoopDao -> DesktopDao, android.content.Context removed. All public method signatures
 * are identical to the Android repository.
 */
class WhoopRepository(private val dao: DesktopDao) {

    constructor(db: DesktopDatabase) : this(DesktopDao(db))

    // MARK: - Device

    suspend fun upsertDevice(id: String, mac: String? = null, name: String? = null) {
        val now = System.currentTimeMillis() / 1000
        val existing = dao.device(id)
        dao.upsertDevice(
            DeviceRow(
                id = id,
                mac = mac,
                name = name,
                firstSeen = existing?.firstSeen ?: now,
                lastSeen = now,
            )
        )
    }

    // MARK: - Insert decoded streams (idempotent by natural key)

    suspend fun insert(streams: StreamBatch, deviceId: String): InsertCounts {
        if (streams.isEmpty) return InsertCounts()

        val hrIds = if (streams.hr.isEmpty()) emptyList() else
            dao.insertHr(streams.hr.map { HrSample(deviceId, it.ts, it.bpm) })
        val rrIds = if (streams.rr.isEmpty()) emptyList() else
            dao.insertRr(streams.rr.map { RrInterval(deviceId, it.ts, it.rrMs) })
        val evIds = if (streams.events.isEmpty()) emptyList() else
            dao.insertEvents(streams.events.map { EventRow(deviceId, it.ts, it.kind, it.payloadJSON) })
        val batIds = if (streams.battery.isEmpty()) emptyList() else
            dao.insertBattery(streams.battery.map { BatterySample(deviceId, it.ts, it.soc, it.mv, it.charging) })
        val spo2Ids = if (streams.spo2.isEmpty()) emptyList() else
            dao.insertSpo2(streams.spo2.map { Spo2Sample(deviceId, it.ts, it.red, it.ir) })
        val skinIds = if (streams.skinTemp.isEmpty()) emptyList() else
            dao.insertSkinTemp(streams.skinTemp.map { SkinTempSample(deviceId, it.ts, it.raw) })
        val stepIds = if (streams.steps.isEmpty()) emptyList() else
            dao.insertSteps(streams.steps.map { StepSample(deviceId, it.ts, it.counter, it.activityClass) })
        if (streams.sleepState.isNotEmpty()) {
            dao.insertSleepState(streams.sleepState.map { SleepStateSampleEntity(deviceId, it.ts, it.state) })
        }
        val respIds = if (streams.resp.isEmpty()) emptyList() else
            dao.insertResp(streams.resp.map { RespSample(deviceId, it.ts, it.raw) })
        val gravIds = if (streams.gravity.isEmpty()) emptyList() else
            dao.insertGravity(streams.gravity.map { GravitySample(deviceId, it.ts, it.x, it.y, it.z) })
        val ppgHrIds = if (streams.ppgHr.isEmpty()) emptyList() else
            dao.insertPpgHr(streams.ppgHr.map { PpgHrSample(deviceId, it.ts, it.bpm, it.conf) })

        return InsertCounts(
            hr = hrIds.countInserted() + ppgHrIds.countInserted(),
            rr = rrIds.countInserted(),
            events = evIds.countInserted(),
            battery = batIds.countInserted(),
            spo2 = spo2Ids.countInserted(),
            skinTemp = skinIds.countInserted(),
            steps = stepIds.countInserted(),
            resp = respIds.countInserted(),
            gravity = gravIds.countInserted(),
        )
    }

    /** #836 — cheap whole-history raw-HR change fingerprint "count:maxTs". */
    suspend fun hrFingerprint(): String = "${dao.countHr()}:${dao.maxHrTs()}"

    // MARK: - Server-derived caches (latest value wins on conflict)

    suspend fun upsertDailyMetrics(days: List<DailyMetric>) = dao.upsertDailyMetrics(days)
    suspend fun upsertSleepSessions(sessions: List<SleepSession>) = dao.upsertSleepSessions(sessions)

    suspend fun deleteComputedDailyInRange(deviceId: String, from: String, to: String) =
        dao.deleteDailyMetricsInRange(deviceId, from, to)

    suspend fun updateSleepSessionTimes(session: SleepSession, newStartTs: Long, newEndTs: Long) {
        val (safeStartTs, safeEndTs) = com.noop.analytics.SleepEditGuard.clampedEditWindow(
            newStartTs, newEndTs, System.currentTimeMillis() / 1000L,
        ) ?: return
        val reclipped = com.noop.analytics.SleepWindowReclip.reclip(
            session.stagesJSON, session.effectiveStartTs, session.endTs, safeStartTs, safeEndTs,
        )
        dao.upsertSleepSessions(
            listOf(session.copy(
                startTsAdjusted = safeStartTs,
                endTs = safeEndTs,
                userEdited = true,
                stagesJSON = reclipped ?: session.stagesJSON,
            )),
        )
    }

    suspend fun deleteSleepSession(session: SleepSession) {
        if (com.noop.analytics.DismissedSleepGuard.writesTombstoneOnDelete(session.userEdited)) {
            dao.insertDismissedSleep(listOf(DismissedSleep(session.deviceId, session.startTs, session.endTs)))
        }
        dao.deleteSleepSession(session.deviceId, session.startTs)
    }

    suspend fun undoDeleteSleepSession(session: SleepSession) {
        dao.deleteDismissedSleep(session.deviceId, session.startTs)
        dao.upsertSleepSessions(listOf(session))
    }

    suspend fun allowSleepReDetection(deviceId: String, startTs: Long) =
        dao.deleteDismissedSleep(deviceId, startTs)

    suspend fun deleteSleepSessionRowOnly(session: SleepSession) {
        dao.deleteSleepSession(session.deviceId, session.startTs)
    }

    suspend fun healImplausibleTimestamps(
        nowSec: Long = System.currentTimeMillis() / 1000L,
        today: String = java.time.LocalDate.now().toString(),
        minTs: Long = com.noop.protocol.MIN_PLAUSIBLE_UNIX,
        futureMargin: Long = com.noop.protocol.FUTURE_MARGIN,
    ): Int {
        val maxTs = nowSec + futureMargin
        val minDay = java.time.Instant.ofEpochSecond(minTs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        var deleted = 0
        deleted += dao.pruneHrByTs(minTs, maxTs)
        deleted += dao.prunePpgHrByTs(minTs, maxTs)
        deleted += dao.pruneRrByTs(minTs, maxTs)
        deleted += dao.pruneSkinTempByTs(minTs, maxTs)
        deleted += dao.pruneStepByTs(minTs, maxTs)
        deleted += dao.pruneRespByTs(minTs, maxTs)
        deleted += dao.pruneGravityByTs(minTs, maxTs)
        deleted += dao.pruneSpo2ByTs(minTs, maxTs)
        deleted += dao.pruneEventByTs(minTs, maxTs)
        deleted += dao.pruneBatteryByTs(minTs, maxTs)
        deleted += dao.pruneDailyMetricByDay(today, minDay)
        deleted += dao.pruneSleepSessionByTs(minTs, maxTs)
        return deleted
    }

    suspend fun addManualNap(strapDeviceId: String, startTs: Long, endTs: Long) {
        val (safeStartTs, safeEndTs) = com.noop.analytics.SleepEditGuard.clampedEditWindow(
            startTs, endTs, System.currentTimeMillis() / 1000L,
        ) ?: return
        val computedId = computedDeviceId(strapDeviceId)
        val stagesJSON = com.noop.analytics.SleepStageHealer.restageFromRaw(this, strapDeviceId, safeStartTs, safeEndTs)
            ?: com.noop.analytics.AnalyticsEngine.encodeStages(
                listOf(com.noop.analytics.StageSegment(start = safeStartTs, end = safeEndTs, stage = "wake")),
            )
        dao.insertSleepSession(
            SleepSession(
                deviceId = computedId,
                startTs = safeStartTs,
                endTs = safeEndTs,
                efficiency = sleepEfficiency(stagesJSON),
                stagesJSON = stagesJSON,
                userEdited = true,
                startTsAdjusted = null,
            ),
        )
    }

    private fun sleepEfficiency(stagesJSON: String?): Double? {
        stagesJSON ?: return null
        val arr = runCatching { org.json.JSONArray(stagesJSON) }.getOrNull() ?: return null
        var asleep = 0.0
        var total = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optLong("start", -1L)
            val e = o.optLong("end", -1L)
            val stage = o.optString("stage")
            if (s < 0 || e <= s) continue
            val dur = (e - s).toDouble()
            total += dur
            if (stage != "wake" && stage != "awake") asleep += dur
        }
        return if (total > 0 && asleep > 0) asleep / total else null
    }

    suspend fun updateSleepStages(deviceId: String, detectedStartTs: Long, stagesJSON: String): Int =
        dao.updateSleepStages(deviceId, detectedStartTs, stagesJSON)

    // MARK: - Per-epoch sleep analytics (v18: motionJSON / sleepStateJSON)

    suspend fun persistSessionMotion(deviceId: String, sessionStart: Long, motionEpochs: List<Double>): Int =
        dao.updateSessionMotion(deviceId, sessionStart, if (motionEpochs.isEmpty()) null else encodeDoubleArray(motionEpochs))

    suspend fun sessionMotion(deviceId: String, sessionStart: Long): List<Double>? =
        dao.sessionMotionJson(deviceId, sessionStart)?.let { decodeDoubleArray(it) }

    suspend fun sessionMotions(strapDeviceId: String, starts: List<Long>): Map<Long, List<Double>> {
        if (starts.isEmpty()) return emptyMap()
        val computedId = computedDeviceId(strapDeviceId)
        val out = HashMap<Long, List<Double>>()
        for (start in starts) {
            val m = dao.sessionMotionJson(computedId, start)?.let { decodeDoubleArray(it) }
            if (!m.isNullOrEmpty()) out[start] = m
        }
        return out
    }

    suspend fun persistSessionSleepState(deviceId: String, sessionStart: Long, states: List<Int>): Int =
        dao.updateSessionSleepState(deviceId, sessionStart, if (states.isEmpty()) null else encodeIntArray(states))

    suspend fun sessionSleepState(deviceId: String, sessionStart: Long): List<Int>? =
        dao.sessionSleepStateJson(deviceId, sessionStart)?.let { decodeIntArray(it) }

    suspend fun upsertMetricSeries(rows: List<MetricSeriesRow>) = dao.upsertMetricSeries(rows)
    suspend fun upsertJournal(rows: List<JournalEntry>) = dao.upsertJournal(rows)
    suspend fun upsertWorkouts(rows: List<WorkoutRow>) = dao.upsertWorkouts(rows)
    suspend fun upsertAppleDaily(rows: List<AppleDaily>) = dao.upsertAppleDaily(rows)

    // MARK: - Live Sessions (silent guardian, v22)

    suspend fun upsertLiveSession(row: LiveSessionRow) = dao.upsertLiveSession(row)
    suspend fun recentLiveSessions(deviceId: String, limit: Int): List<LiveSessionRow> =
        dao.recentLiveSessions(deviceId, limit)

    // MARK: - Lab Book markers

    suspend fun upsertLabMarkers(rows: List<LabMarkerRow>) = dao.upsertLabMarkers(rows)
    suspend fun deleteLabMarker(id: String): Boolean = dao.deleteLabMarker(id)
    suspend fun labMarkersByKey(deviceId: String, markerKey: String) = dao.labMarkersByKey(deviceId, markerKey)
    suspend fun labMarkersByCategory(deviceId: String, category: String) = dao.labMarkersByCategory(deviceId, category)
    suspend fun markerKeysPresent(deviceId: String) = dao.markerKeysPresent(deviceId)

    // MARK: - Reads

    suspend fun hrSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.hrSamples(deviceId, from, to, limit)

    suspend fun hrSamplesUnion(activeDeviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<HrSample> = mergeHrByTs(importedSourceIds(activeDeviceId).map { dao.hrSamples(it, from, to, limit) })

    suspend fun rawHrSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.rawHrSamples(deviceId, from, to, limit)

    suspend fun ppgHrSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.ppgHrSamples(deviceId, from, to, limit)

    suspend fun hrBuckets(deviceId: String, from: Long, to: Long, bucketSeconds: Long = 300L) =
        dao.hrBuckets(deviceId, from, to, bucketSeconds)

    suspend fun hrBucketsUnion(activeDeviceId: String, from: Long, to: Long, bucketSeconds: Long = 300L):
        List<HrBucket> = mergeHrBucketsByStart(
            importedSourceIds(activeDeviceId).map { dao.hrBuckets(it, from, to, bucketSeconds) },
        )

    suspend fun fillWorkoutHrFromStrap(
        rows: List<WorkoutRow>,
        strapDeviceId: String = "my-whoop",
        minSamples: Long = 60,
        cap: Int = 300,
        strainMaxHR: Double? = null,
        strainSex: String = "male",
    ): List<WorkoutRow> {
        var budget = cap
        return rows.map { row ->
            if (row.endTs <= row.startTs || budget <= 0) return@map row
            val src = row.source.lowercase()
            val strapNative = src == "manual" || src.endsWith("-noop")
            val needsStrainFill = strapNative && row.strain == null && strainMaxHR != null
            if (!strapNative && row.avgHr != null && !needsStrainFill) return@map row
            budget -= 1
            val stats = dao.hrWindowStats(strapDeviceId, row.startTs, row.endTs)
            if (stats.n < minSamples || stats.avg == null || stats.max == null) return@map row
            val filledStrain = if (needsStrainFill && strainMaxHR != null) {
                val samples = dao.hrSamples(strapDeviceId, row.startTs, row.endTs, 8000)
                com.noop.analytics.StrainScorer.strain(samples, maxHR = strainMaxHR, sex = strainSex)
            } else null
            if (strapNative) {
                row.copy(avgHr = stats.avg.roundToInt(), maxHr = stats.max,
                         strain = row.strain ?: filledStrain)
            } else {
                row.copy(avgHr = stats.avg.roundToInt(), maxHr = row.maxHr ?: stats.max)
            }
        }
    }

    suspend fun rrIntervals(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.rrIntervals(deviceId, from, to, limit)

    suspend fun events(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.events(deviceId, from, to, limit)

    suspend fun batterySamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.batterySamples(deviceId, from, to, limit)

    suspend fun spo2Samples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.spo2Samples(deviceId, from, to, limit)

    suspend fun skinTempSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.skinTempSamples(deviceId, from, to, limit)

    suspend fun stepSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.stepSamples(deviceId, from, to, limit)

    suspend fun sleepStateSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<SleepStateRow> =
        dao.sleepStateSamples(deviceId, from, to, limit).map { SleepStateRow(it.ts, it.state) }

    suspend fun stepActivityClassLatestUnion(activeDeviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        Int? = latestActivityClass(importedSourceIds(activeDeviceId).map { dao.stepSamples(it, from, to, limit) })

    suspend fun deleteComputedWorkouts(deviceId: String, sport: String, from: Long, to: Long) =
        dao.deleteWorkoutsBySport(deviceId, sport, from, to)

    // MARK: - Workout editing

    suspend fun dismissedDetected(strapDeviceId: String = "my-whoop"): List<DismissedWorkout> =
        dao.dismissedWorkouts(computedDeviceId(strapDeviceId))

    suspend fun dismissedSleeps(strapDeviceId: String = "my-whoop"): List<DismissedSleep> =
        dao.dismissedSleeps(strapDeviceId) + dao.dismissedSleeps(computedDeviceId(strapDeviceId))

    suspend fun saveManualWorkout(row: WorkoutRow, replacing: WorkoutRow? = null) {
        if (replacing != null && replacing.source.lowercase().endsWith("-noop")) {
            dismissDetected(replacing)
        } else if (replacing != null && (replacing.startTs != row.startTs || replacing.sport != row.sport)) {
            dao.deleteWorkoutByKey(replacing.deviceId, replacing.startTs, replacing.sport)
        }
        dao.upsertWorkouts(listOf(row))
    }

    suspend fun relabelDetected(row: WorkoutRow, sport: String, strapDeviceId: String = "my-whoop") {
        val trimmed = sport.trim()
        if (trimmed.isEmpty()) return
        val manual = row.copy(deviceId = strapDeviceId, sport = trimmed, source = "manual")
        dao.upsertWorkouts(listOf(manual))
        dao.deleteWorkoutsBySport(computedDeviceId(strapDeviceId), "detected", row.startTs, row.startTs)
    }

    suspend fun dismissDetected(row: WorkoutRow) {
        if (!row.source.lowercase().endsWith("-noop")) return
        dao.insertDismissed(listOf(DismissedWorkout(row.deviceId, row.startTs, row.endTs)))
        dao.deleteWorkoutsBySport(row.deviceId, row.sport, row.startTs, row.startTs)
    }

    suspend fun deleteWorkout(row: WorkoutRow) {
        if (row.source.lowercase().endsWith("-noop")) { dismissDetected(row); return }
        dao.deleteWorkoutByKey(row.deviceId, row.startTs, row.sport)
    }

    suspend fun mergeWorkouts(originals: List<WorkoutRow>, merged: WorkoutRow) {
        if (originals.size < 2) return
        val keptRoute = originals.mapNotNull { it.routePolyline }.maxByOrNull { it.length }
        val mergedWithRoute = if (keptRoute != null) merged.copy(routePolyline = keptRoute) else merged
        saveManualWorkout(mergedWithRoute)
        for (r in originals) {
            if (r.startTs == merged.startTs && r.sport == merged.sport) continue
            when {
                r.source.lowercase().endsWith("-noop") -> dismissDetected(r)
                r.source.lowercase() == "manual" -> dao.deleteWorkoutByKey(r.deviceId, r.startTs, r.sport)
                else -> continue
            }
        }
    }

    suspend fun bulkDeleteWorkouts(rows: List<WorkoutRow>) {
        for (r in rows) {
            when {
                r.source.lowercase().endsWith("-noop") -> dismissDetected(r)
                r.source.lowercase() == "manual" -> dao.deleteWorkoutByKey(r.deviceId, r.startTs, r.sport)
                else -> continue
            }
        }
    }

    suspend fun respSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.respSamples(deviceId, from, to, limit)

    suspend fun gravitySamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.gravitySamples(deviceId, from, to, limit)

    suspend fun sleepSessions(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.sleepSessions(deviceId, from, to, limit)

    suspend fun habitualMidsleepSec(deviceId: String, days: Int = 4000): Long? {
        val now = System.currentTimeMillis() / 1000L
        val lo = now - days * 86_400L
        val hi = now + 86_400L
        val imported = dedupSleepBlocks(importedSourceIds(deviceId).flatMap { dao.sleepSessions(it, lo, hi, 4000) })
        val computed = dedupSleepBlocks(computedSourceIds(deviceId).flatMap { dao.sleepSessions(it, lo, hi, 4000) })
        val offsetSec = (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toLong()
        val blocks = (imported + computed).mapNotNull { s ->
            val start = s.effectiveStartTs
            val end = s.endTs
            if (end <= start) {
                null
            } else {
                val mid = start + (end - start) / 2
                com.noop.analytics.SleepStageTotals.HistoryBlock(
                    start, end, com.noop.analytics.AnalyticsEngine.dayString(mid, offsetSec),
                )
            }
        }
        return com.noop.analytics.SleepStageTotals.habitualMidsleepSec(blocks, offsetSec)
    }

    suspend fun metricSeries(deviceId: String, key: String, from: String, to: String) =
        dao.metricSeries(deviceId, key, from, to)

    suspend fun metricKeys(deviceId: String): List<String> = dao.metricKeys(deviceId)

    suspend fun workouts(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<WorkoutRow> =
        dao.workouts(deviceId, from, to, limit)

    suspend fun journal(deviceId: String, from: String, to: String): List<JournalEntry> =
        dao.journal(deviceId, from, to)

    suspend fun deleteJournalEntry(deviceId: String, day: String, question: String) =
        dao.deleteJournalEntry(deviceId, day, question)

    suspend fun appleDaily(deviceId: String, from: String, to: String): List<AppleDaily> =
        dao.appleDaily(deviceId, from, to)

    suspend fun days(deviceId: String): List<DailyMetric> = dao.days(deviceId)

    suspend fun refileLegacyHealthConnect() {
        dao.reassignWorkoutsBySource(from = "apple-health", to = "health-connect", source = "health-connect")
        if (dao.metricSeriesCount("apple-health") == 0) {
            dao.reassignAppleDaily(from = "apple-health", to = "health-connect")
            upsertDevice("health-connect", name = "Health Connect")
        }
    }

    // MARK: - Merged reads

    fun computedDeviceId(deviceId: String): String = "$deviceId-noop"

    fun importedSourceIds(activeDeviceId: String): List<String> = importedSourceIdsFor(activeDeviceId)
    fun computedSourceIds(activeDeviceId: String): List<String> = computedSourceIdsFor(activeDeviceId)

    suspend fun dataVolumeSnapshot(
        strapDeviceId: String = WHOOP_SOURCE,
    ): com.noop.analytics.DataVolume {
        val dbRows = runCatching {
            dao.countHr() + dao.countRr() + dao.countEvents() + dao.countSpo2() +
                dao.countSkinTemp() + dao.countSteps() + dao.countResp() + dao.countGravity()
        }.getOrDefault(0)
        val imported = runCatching { dao.days(strapDeviceId) }.getOrDefault(emptyList())
        val workouts = runCatching {
            dao.workouts(strapDeviceId, 0L, 4_102_444_800L, 1_000_000)
        }.getOrDefault(emptyList())
        val computed = runCatching { dao.days(computedDeviceId(strapDeviceId)) }.getOrDefault(emptyList())
        val apple = runCatching { dao.days(APPLE_HEALTH_SOURCE) }.getOrDefault(emptyList())
        val renderDays = HashSet<String>()
        for (m in imported) renderDays.add(m.day)
        for (m in computed) renderDays.add(m.day)
        for (m in apple) renderDays.add(m.day)
        return com.noop.analytics.DataVolume(
            dbRows = dbRows,
            importedDays = imported.size,
            workouts = workouts.size,
            lastRenderRows = renderDays.size,
        )
    }

    suspend fun storageRowCounts(): Map<String, Int> = runCatching {
        mapOf(
            "hr" to dao.countHr(), "rr" to dao.countRr(), "events" to dao.countEvents(),
            "battery" to dao.countBattery(), "spo2" to dao.countSpo2(),
            "skinTemp" to dao.countSkinTemp(), "steps" to dao.countSteps(),
            "resp" to dao.countResp(), "gravity" to dao.countGravity(),
        )
    }.getOrDefault(emptyMap())

    suspend fun daysMerged(deviceId: String): List<DailyMetric> {
        val imported = unionByDay(importedSourceIds(deviceId).map { dao.days(it) })
        val computed = unionByDay(computedSourceIds(deviceId).map { dao.days(it) })
        val editedSessions = computedSourceIds(deviceId).flatMap { dao.editedSleepSessions(it) }
        return mergeDaily(imported = imported, computed = computed, userEditedDays = userEditedDays(editedSessions))
    }

    private fun unionDaysFlow(flows: List<Flow<List<DailyMetric>>>): Flow<List<DailyMetric>> =
        if (flows.size == 1) flows[0]
        else combine(flows) { arrays -> unionByDay(arrays.toList()) }

    fun daysMergedFlow(deviceId: String): Flow<List<DailyMetric>> =
        combine(
            unionDaysFlow(importedSourceIds(deviceId).map { dao.daysFlow(it) }),
            unionDaysFlow(computedSourceIds(deviceId).map { dao.daysFlow(it) }),
            editedSleepSessionsFlow(deviceId),
        ) { imported, computed, edited ->
            mergeDaily(imported = imported, computed = computed, userEditedDays = userEditedDays(edited))
        }

    fun recentDaysMergedFlow(deviceId: String): Flow<List<DailyMetric>> =
        combine(
            unionDaysFlow(importedSourceIds(deviceId).map { dao.recentDaysFlow(it, RECENT_DAYS_CAP) }),
            unionDaysFlow(computedSourceIds(deviceId).map { dao.recentDaysFlow(it, RECENT_DAYS_CAP) }),
            editedSleepSessionsFlow(deviceId),
        ) { imported, computed, edited ->
            mergeDaily(imported = imported, computed = computed, userEditedDays = userEditedDays(edited))
        }

    private fun editedSleepSessionsFlow(deviceId: String): Flow<List<SleepSession>> {
        val flows = computedSourceIds(deviceId).map { dao.editedSleepSessionsFlow(it) }
        return if (flows.size == 1) flows[0]
        else combine(flows) { arrays -> arrays.flatMap { it } }
    }

    suspend fun sleepSessionsMerged(
        deviceId: String,
        from: Long,
        to: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<SleepSession> = mergeSleep(
        imported = importedSourceIds(deviceId).reversed().flatMap { dao.sleepSessions(it, from, to, limit) },
        computed = computedSourceIds(deviceId).reversed().flatMap { dao.sleepSessions(it, from, to, limit) },
    )

    suspend fun sleepSessionsUnion(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<SleepSession> =
        dedupSleepBlocks(importedSourceIds(deviceId).flatMap { dao.sleepSessions(it, from, to, limit) })

    suspend fun computedSleepSessionsUnion(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<SleepSession> =
        dedupSleepBlocks(computedSourceIds(deviceId).flatMap { dao.sleepSessions(it, from, to, limit) })

    suspend fun dailyMetrics(deviceId: String, from: String, to: String): List<DailyMetric> =
        dao.dailyMetricsRange(deviceId, from, to)

    // MARK: - Cross-source resolver (PR#196)

    data class ResolvedMetricPoint(
        val day: String,
        val value: Double,
        val source: String,
        val sourceKey: String,
    )

    data class MetricSourceCandidate(val source: String, val key: String)

    internal data class CandidateRow(
        val day: String,
        val value: Double,
        val weakSleepTotal: Boolean = false,
    )

    data class MetricSeriesResolution(
        val requestedSource: String,
        val candidates: List<MetricSourceCandidate>,
        val points: List<ResolvedMetricPoint>,
    ) {
        val values: List<Pair<String, Double>> get() = points.map { it.day to it.value }

        val usedSources: List<String>
            get() {
                val seen = LinkedHashSet<String>()
                for (p in points) seen.add(p.source)
                return seen.toList()
            }
    }

    suspend fun resolvedSeries(
        key: String,
        preferredSource: String,
        from: String,
        to: String,
        strapDeviceId: String = "my-whoop",
    ): MetricSeriesResolution {
        val candidates = sourceCandidates(key, preferredSource, strapDeviceId)
        val perCandidate = candidates.map { it to resolvedRows(it, from, to) }
        return MetricSeriesResolution(preferredSource, candidates, resolveFirstWins(perCandidate))
    }

    private suspend fun resolvedRows(
        candidate: MetricSourceCandidate,
        from: String,
        to: String,
    ): List<CandidateRow> {
        val byDay = LinkedHashMap<String, CandidateRow>()
        for (row in dao.metricSeries(candidate.source, candidate.key, from, to)) {
            byDay[row.day] = CandidateRow(row.day, row.value)
        }
        val sleepTotalKey = candidate.key == "sleep_total_min" || candidate.key == "asleep_min"
        for (row in dao.dailyMetricsRange(candidate.source, from, bufferDayAfter(to))) {
            if (!byDay.containsKey(row.day)) {
                dailyColumn(candidate.key, row)?.let {
                    byDay[row.day] = CandidateRow(row.day, it, sleepTotalKey && bareSleepAggregate(row))
                }
            }
        }
        return byDay.values.sortedBy { it.day }
    }

    private fun bufferDayAfter(day: String): String =
        runCatching { java.time.LocalDate.parse(day).plusDays(1).toString() }.getOrDefault(day)

    suspend fun freshness(strapDeviceId: String = "my-whoop"): DataFreshness {
        val to = freshnessDayKey(1)
        val from = freshnessDayKey(-4000)
        val imported = dao.dailyMetricsRange(strapDeviceId, from, to)
        val computed = dao.dailyMetricsRange(computedDeviceId(strapDeviceId), from, to)
        val apple = dao.dailyMetricsRange(APPLE_HEALTH_SOURCE, from, to)
        val now = System.currentTimeMillis() / 1000L
        val lo = now - 4000L * 86_400L
        val hi = now + 86_400L
        val importedSleeps = dao.sleepSessions(strapDeviceId, lo, hi, DEFAULT_LIMIT)
        val computedSleeps = dao.sleepSessions(computedDeviceId(strapDeviceId), lo, hi, DEFAULT_LIMIT)
        val days = (imported + computed + apple).map { it.day }
        return DataFreshness(
            importedDays = imported.size,
            computedDays = computed.size,
            appleDays = apple.size,
            importedSleeps = importedSleeps.size,
            computedSleeps = computedSleeps.size,
            earliestDay = days.minOrNull(),
            latestDay = days.maxOrNull(),
        )
    }

    private fun freshnessDayKey(deltaDays: Int): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.add(java.util.Calendar.DAY_OF_YEAR, deltaDays)
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    // MARK: - Flows

    fun daysFlow(deviceId: String): Flow<List<DailyMetric>> = dao.daysFlow(deviceId)

    // MARK: - Frontier / convenience

    suspend fun insertHr(rows: List<HrSample>) = dao.insertHr(rows)

    suspend fun latestHrSampleTs(deviceId: String): Long? = dao.latestHrSampleTs(deviceId)
    suspend fun latestHr(deviceId: String): HrSample? = dao.latestHr(deviceId)
    suspend fun latestBattery(deviceId: String): BatterySample? = dao.latestBattery(deviceId)

    companion object {
        const val DEFAULT_LIMIT = 100_000
        const val RECENT_DAYS_CAP = 800

        const val WHOOP_SOURCE = "my-whoop"
        const val APPLE_HEALTH_SOURCE = "apple-health"
        const val HEALTH_CONNECT_SOURCE = "health-connect"

        fun importedSourceIdsFor(activeDeviceId: String): List<String> =
            if (activeDeviceId == WHOOP_SOURCE) listOf(WHOOP_SOURCE)
            else listOf(activeDeviceId, WHOOP_SOURCE)

        fun computedSourceIdsFor(activeDeviceId: String): List<String> =
            importedSourceIdsFor(activeDeviceId).map { "$it-noop" }

        internal fun dedupSleepBlocks(sessions: List<SleepSession>): List<SleepSession> {
            val seen = HashSet<Pair<Long, Long>>()
            return sessions.filter { seen.add(it.startTs to it.endTs) }
        }

        /** Build a repository backed by the process-wide singleton database. */
        fun from(dbPath: String): WhoopRepository = WhoopRepository(DesktopDatabase.get(dbPath))

        // MARK: - Compact per-epoch JSON (v18 motionJSON / sleepStateJSON)

        internal fun encodeDouble(x: Double): String =
            if (x.isFinite() && x == kotlin.math.floor(x) && !x.isInfinite()) x.toLong().toString() else x.toString()

        internal fun encodeDoubleArray(xs: List<Double>): String =
            xs.joinToString(separator = ",", prefix = "[", postfix = "]") { encodeDouble(it) }

        internal fun encodeIntArray(xs: List<Int>): String =
            xs.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toString() }

        internal fun decodeDoubleArray(json: String): List<Double>? = runCatching {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { arr.getDouble(it) }
        }.getOrNull()

        internal fun decodeIntArray(json: String): List<Int>? = runCatching {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { arr.getInt(it) }
        }.getOrNull()

        internal fun sourceCandidates(
            key: String,
            preferredSource: String,
            strapDeviceId: String,
        ): List<MetricSourceCandidate> {
            val computedSource = "$strapDeviceId-noop"
            fun uniqued(cs: List<MetricSourceCandidate>): List<MetricSourceCandidate> {
                val seen = LinkedHashSet<MetricSourceCandidate>()
                for (c in cs) seen.add(c)
                return seen.toList()
            }
            if (preferredSource == WHOOP_SOURCE || preferredSource == strapDeviceId) {
                val candidates = mutableListOf(
                    MetricSourceCandidate(strapDeviceId, key),
                    MetricSourceCandidate(computedSource, key),
                    MetricSourceCandidate(WHOOP_SOURCE, key),
                    MetricSourceCandidate("$WHOOP_SOURCE-noop", key),
                )
                appleCompatibleKey(key)?.let {
                    candidates.add(MetricSourceCandidate(APPLE_HEALTH_SOURCE, it))
                }
                return uniqued(candidates)
            }
            if (preferredSource == APPLE_HEALTH_SOURCE) {
                val candidates = mutableListOf(MetricSourceCandidate(APPLE_HEALTH_SOURCE, key))
                candidates.add(MetricSourceCandidate(HEALTH_CONNECT_SOURCE, key))
                if (noopComputedCanFillAppleMetric(key)) {
                    candidates.add(MetricSourceCandidate(computedSource, key))
                }
                return uniqued(candidates)
            }
            return listOf(MetricSourceCandidate(preferredSource, key))
        }

        internal fun appleCompatibleKey(key: String): String? = when (key) {
            "rhr" -> "resting_hr"
            "hrv", "spo2", "resp_rate", "avg_hr", "max_hr", "in_bed_min", "active_kcal" -> key
            "sleep_total_min" -> "asleep_min"
            "sleep_deep_min" -> "deep_min"
            "sleep_rem_min" -> "rem_min"
            "sleep_light_min" -> "core_min"
            else -> null
        }

        private fun noopComputedCanFillAppleMetric(key: String): Boolean = when (key) {
            "steps", "active_kcal" -> true
            else -> false
        }

        internal fun dailyColumn(key: String, d: DailyMetric): Double? = when (key) {
            "recovery" -> d.recovery
            "hrv" -> d.avgHrv
            "rhr", "resting_hr" -> d.restingHr?.toDouble()
            "strain" -> d.strain
            "resp_rate" -> d.respRateBpm
            "spo2" -> d.spo2Pct
            "skin_temp" -> d.skinTempDevC
            "sleep_total_min", "asleep_min" -> d.totalSleepMin
            "sleep_efficiency" -> d.efficiency
            "sleep_deep_min", "deep_min" -> d.deepMin
            "sleep_rem_min", "rem_min" -> d.remMin
            "sleep_light_min", "core_min" -> d.lightMin
            "sleep_performance" -> com.noop.analytics.RestScorer.restFromDaily(d)
            "steps" -> d.steps?.toDouble()
            "active_kcal", "energy_kcal" -> d.activeKcalEst
            else -> null
        }

        internal fun bareSleepAggregate(d: DailyMetric): Boolean =
            d.totalSleepMin != null && d.efficiency == null &&
                d.deepMin == null && d.remMin == null && d.lightMin == null

        internal fun resolveFirstWins(
            perCandidate: List<Pair<MetricSourceCandidate, List<CandidateRow>>>,
        ): List<ResolvedMetricPoint> {
            val byDay = LinkedHashMap<String, ResolvedMetricPoint>()
            val weakDays = HashSet<String>()
            for ((candidate, rows) in perCandidate) {
                for (row in rows) {
                    val taken = byDay.containsKey(row.day)
                    if (!taken || (row.day in weakDays && !row.weakSleepTotal)) {
                        byDay[row.day] = ResolvedMetricPoint(row.day, row.value, candidate.source, candidate.key)
                        if (row.weakSleepTotal) weakDays.add(row.day) else weakDays.remove(row.day)
                    }
                }
            }
            return byDay.values.sortedBy { it.day }
        }

        internal fun unionByDay(lists: List<List<DailyMetric>>): List<DailyMetric> {
            if (lists.size == 1) return lists[0]
            val byDay = LinkedHashMap<String, DailyMetric>()
            for (list in lists) for (d in list) byDay.putIfAbsent(d.day, d)
            return byDay.values.toList()
        }

        internal fun mergeHrByTs(lists: List<List<HrSample>>): List<HrSample> {
            if (lists.size == 1) return lists[0]
            val byTs = LinkedHashMap<Long, HrSample>()
            for (list in lists) for (s in list) byTs.putIfAbsent(s.ts, s)
            return byTs.values.sortedBy { it.ts }
        }

        internal fun mergeHrBucketsByStart(lists: List<List<HrBucket>>): List<HrBucket> {
            if (lists.size == 1) return lists[0]
            val byStart = LinkedHashMap<Long, HrBucket>()
            for (list in lists) for (b in list) byStart.putIfAbsent(b.bucket, b)
            return byStart.values.sortedBy { it.bucket }
        }

        internal fun latestActivityClass(lists: List<List<StepSample>>): Int? {
            var bestTs = Long.MIN_VALUE
            var bestClass: Int? = null
            for (list in lists) for (s in list) {
                if (s.activityClass != null && s.ts > bestTs) {
                    bestTs = s.ts
                    bestClass = s.activityClass
                }
            }
            return bestClass
        }

        internal fun mergeDaily(
            imported: List<DailyMetric>,
            computed: List<DailyMetric>,
            userEditedDays: Set<String> = emptySet(),
        ): List<DailyMetric> {
            val byDay = LinkedHashMap<String, DailyMetric>()
            for (d in computed) byDay[d.day] = d
            for (d in imported) {
                val c = byDay[d.day]
                val merged = if (c == null) d else d.copy(
                    totalSleepMin = d.totalSleepMin ?: c.totalSleepMin,
                    efficiency = d.efficiency ?: c.efficiency,
                    deepMin = d.deepMin ?: c.deepMin,
                    remMin = d.remMin ?: c.remMin,
                    lightMin = d.lightMin ?: c.lightMin,
                    disturbances = d.disturbances ?: c.disturbances,
                    restingHr = d.restingHr ?: c.restingHr,
                    avgHrv = d.avgHrv ?: c.avgHrv,
                    recovery = d.recovery ?: c.recovery,
                    strain = d.strain ?: c.strain,
                    exerciseCount = d.exerciseCount ?: c.exerciseCount,
                    spo2Pct = d.spo2Pct ?: c.spo2Pct,
                    skinTempDevC = d.skinTempDevC ?: c.skinTempDevC,
                    respRateBpm = d.respRateBpm ?: c.respRateBpm,
                    steps = d.steps ?: c.steps,
                    activeKcalEst = d.activeKcalEst ?: c.activeKcalEst,
                )
                val bareImportedSleepTotal = bareSleepAggregate(d)
                byDay[d.day] = if (c != null &&
                    (d.day in userEditedDays ||
                        (bareImportedSleepTotal && c.totalSleepMin != null && !bareSleepAggregate(c)))
                ) {
                    merged.copy(
                        totalSleepMin = c.totalSleepMin,
                        efficiency = c.efficiency,
                        deepMin = c.deepMin,
                        remMin = c.remMin,
                        lightMin = c.lightMin,
                        disturbances = c.disturbances,
                    )
                } else {
                    merged
                }
            }
            return byDay.values.sortedBy { it.day }
        }

        internal fun userEditedDays(sessions: List<SleepSession>): Set<String> {
            val days = HashSet<String>()
            for (s in sessions) {
                if (!s.userEdited) continue
                val offsetSec = (java.util.TimeZone.getDefault().getOffset(s.endTs * 1000) / 1000).toLong()
                days.add(com.noop.analytics.AnalyticsEngine.dayString(s.endTs, offsetSec))
            }
            return days
        }

        internal fun mergeSleep(
            imported: List<SleepSession>,
            computed: List<SleepSession>,
        ): List<SleepSession> {
            fun endDay(s: SleepSession): String {
                val offsetSec = (java.util.TimeZone.getDefault().getOffset(s.endTs * 1000) / 1000).toLong()
                return com.noop.analytics.AnalyticsEngine.dayString(s.endTs, offsetSec)
            }
            val importedDays = imported.mapTo(HashSet()) { endDay(it) }
            val out = ArrayList<SleepSession>(imported.size + computed.size)
            out.addAll(imported)
            for (s in computed) if (endDay(s) !in importedDays) out.add(s)
            return out.sortedBy { it.startTs }
        }
    }
}

/** INSERT OR IGNORE returns the new rowid (1), or -1 when the row was skipped. */
private fun List<Long>.countInserted(): Int = count { it != -1L }
