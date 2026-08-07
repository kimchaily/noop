package com.noop.data

/*
 * Desktop (SQLite JDBC) data entities mirroring the Android Room entities in
 * Entities.kt + PairedDevice.kt, which themselves mirror the verified GRDB schema
 * in Packages/WhoopStore/Sources/WhoopStore/Database.swift (+ MetricsCache.swift).
 *
 * All Room annotations (@Entity, @PrimaryKey, @ColumnInfo, @Index) have been
 * removed; the table/column mapping is encoded in DesktopDatabase.kt's raw SQL
 * and DesktopDao.kt's PreparedStatement SQL strings. Field names and types are
 * identical to the Android entities so the repository and analytics layers are
 * unchanged.
 *
 * Natural keys are preserved EXACTLY so insert dedupe (INSERT OR IGNORE) behaves
 * identically to Room's OnConflictStrategy.IGNORE and Swift's ON CONFLICT(...) DO NOTHING:
 *   - hrSample        PK (deviceId, ts)
 *   - rrInterval      PK (deviceId, ts, rrMs)
 *   - event           PK (deviceId, ts, kind)
 *   - battery         PK (deviceId, ts)
 *   - spo2Sample      PK (deviceId, ts)
 *   - skinTempSample  PK (deviceId, ts)
 *   - respSample      PK (deviceId, ts)
 *   - gravitySample   PK (deviceId, ts)
 *   - dailyMetric     PK (deviceId, day)
 *   - sleepSession    PK (deviceId, startTs)
 *   - device          PK (id)
 *   - journal         PK (deviceId, day, question)
 *   - workout         PK (deviceId, startTs, sport)
 *   - appleDaily      PK (deviceId, day)
 *
 * `ts` columns are wall-clock unix SECONDS (Swift uses Int -> Kotlin Long for safety).
 */

/** Device row. Swift `device` table (Database.swift v1). Natural key = id. */
data class DeviceRow(
    val id: String,
    val mac: String? = null,
    val name: String? = null,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
)

/** Heart-rate sample. Swift `hrSample` (v1). PK (deviceId, ts). */
data class HrSample(
    val deviceId: String,
    val ts: Long,
    val bpm: Int,
    val synced: Int = 0,
)

/**
 * HR derived from the WHOOP 5/MG v26 optical PPG waveform (#156). The v26 record stores no
 * per-second bpm (HR is PPG-derived on-device), so PpgHr reconstructs it by autocorrelation.
 * Kept in its own table (NOT merged into hrSample) so a real sensor HR is never confused with
 * a derived estimate; conf (0..1) records the autocorrelation strength. PK (deviceId, ts).
 */
data class PpgHrSample(
    val deviceId: String,
    val ts: Long,
    val bpm: Int,
    val conf: Double,
    val synced: Int = 0,
)

/** One downsampled HR point, the bucket's start (unix seconds) + the mean bpm over it. Query
 *  result of hrBuckets, not a table. Mirrors the macOS HRBucket. */
data class HrBucket(
    val bucket: Long,
    val avgBpm: Double,
)

/** Aggregate HR over a time window, sample count + avg/max bpm. Query result of hrWindowStats,
 *  not a table. Used to derive a workout's HR from strap samples when the imported session
 *  carries none (#77). avg/max are null when n == 0. */
data class HrWindowStats(
    val n: Long,
    val avg: Double?,
    val max: Int?,
)

/** R-R interval. Swift `rrInterval` (v1). PK (deviceId, ts, rrMs), multiple R-R per ts. */
data class RrInterval(
    val deviceId: String,
    val ts: Long,
    val rrMs: Int,
    val synced: Int = 0,
)

/**
 * Strap event. Swift `event` (v1). PK (deviceId, ts, kind).
 * payloadJSON is the deterministic (sorted-keys) JSON of the remaining parsed fields,
 * with event/event_timestamp removed.
 */
data class EventRow(
    val deviceId: String,
    val ts: Long,
    val kind: String,
    val payloadJSON: String,
    val synced: Int = 0,
)

/**
 * Battery sample. Swift `battery` (v1 + v6 charging). PK (deviceId, ts).
 * soc is state-of-charge percent (nullable), mv millivolts (nullable),
 * charging only set by BATTERY_LEVEL events (nullable otherwise).
 */
data class BatterySample(
    val deviceId: String,
    val ts: Long,
    val soc: Double? = null,
    val mv: Int? = null,
    val charging: Boolean? = null,
    val synced: Int = 0,
)

/** SpO2 raw-ADC sample (type-47). Swift `spo2Sample` (v3). PK (deviceId, ts). */
data class Spo2Sample(
    val deviceId: String,
    val ts: Long,
    val red: Int,
    val ir: Int,
    val synced: Int = 0,
)

/** Skin-temperature raw-ADC sample (type-47). Swift `skinTempSample` (v3). PK (deviceId, ts). */
data class SkinTempSample(
    val deviceId: String,
    val ts: Long,
    val raw: Int,
    val synced: Int = 0,
)

/**
 * Step / motion counter sample (WHOOP5 type-47 step_motion_counter@57). PK (deviceId, ts).
 * counter is the device's CUMULATIVE u16 running step counter (0..65535, wraps). It is NOT a
 * per-sample delta, the daily step total is derived in AnalyticsEngine by summing positive
 * consecutive deltas (with u16 wraparound handling). APPROXIMATE: @57's step semantics are an
 * on-device estimate, unverified against the official WHOOP app. (#78)
 */
data class StepSample(
    val deviceId: String,
    val ts: Long,
    val counter: Int,
    val activityClass: Int? = null,
    val synced: Int = 0,
)

/**
 * The strap's OWN per-record band sleep_state (#175). The decoder reads the v18 @81 high nibble
 * ((sb ushr 4) and 3) as 0 wake / 1 still / 2 asleep / 3 up. PK (deviceId, ts).
 * Swift SleepStateSample.
 */
data class SleepStateSampleEntity(
    val deviceId: String,
    val ts: Long,
    val state: Int,
)

/** Respiration raw-ADC sample (type-47). Swift `respSample` (v3). PK (deviceId, ts). */
data class RespSample(
    val deviceId: String,
    val ts: Long,
    val raw: Int,
    val synced: Int = 0,
)

/** Gravity vector sample (type-47, unit "g"). Swift `gravitySample` (v3). PK (deviceId, ts). */
data class GravitySample(
    val deviceId: String,
    val ts: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val synced: Int = 0,
)

/**
 * Cached server-computed daily metrics. Swift `dailyMetric` (v4 + v7).
 * Natural key (deviceId, day) where day is "YYYY-MM-DD". All metric columns nullable.
 */
data class DailyMetric(
    val deviceId: String,
    val day: String,
    val totalSleepMin: Double? = null,
    val efficiency: Double? = null,
    val deepMin: Double? = null,
    val remMin: Double? = null,
    val lightMin: Double? = null,
    val disturbances: Int? = null,
    val restingHr: Int? = null,
    val avgHrv: Double? = null,
    val recovery: Double? = null,
    val strain: Double? = null,
    val exerciseCount: Int? = null,
    val spo2Pct: Double? = null,
    val skinTempDevC: Double? = null,
    val respRateBpm: Double? = null,
    val steps: Int? = null,
    val activeKcalEst: Double? = null,
)

/**
 * Cached server-computed sleep session. Swift `sleepSession` (v4 + v13 userEdited + v14 startTsAdjusted).
 * Natural key (deviceId, startTs). stagesJSON is the verbatim stage-segments JSON array.
 *
 * Durable bed/wake editing (port of iOS PR #395):
 *   - userEdited: set true when the user hand-corrects this night's bed/wake time.
 *   - startTsAdjusted: the hand-set bed (onset) time. startTs stays the IMMUTABLE detected
 *     primary key. Display / sort / re-staging use effectiveStartTs.
 */
data class SleepSession(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
    val efficiency: Double? = null,
    val restingHr: Int? = null,
    val avgHrv: Double? = null,
    val stagesJSON: String? = null,
    val userEdited: Boolean = false,
    val startTsAdjusted: Long? = null,
    val motionJSON: String? = null,
    val sleepStateJSON: String? = null,
) {
    val effectiveStartTs: Long get() = startTsAdjusted ?: startTs

    val durationHours: Double get() = (endTs - effectiveStartTs) / 3600.0

    val isNapShaped: Boolean
        get() {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = effectiveStartTs * 1000L }
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val overnightOnset = h >= 20 || h < 10
            return durationHours < NAP_MAX_HOURS || !overnightOnset
        }

    companion object {
        const val NAP_MAX_HOURS: Double = 3.0
    }
}

/**
 * Generic long-format metric store. Swift `metricSeries` (v9).
 * Natural key (deviceId, day, key); value is always a REAL.
 */
data class MetricSeriesRow(
    val deviceId: String,
    val day: String,
    val key: String,
    val value: Double,
)

/**
 * Lab Book marker reading (Health Records pillar). Swift `labMarker` (Database.swift v17).
 * id is the client-generated stable primary key; the natural key
 * (deviceId, markerKey, takenAt, source) is a UNIQUE index so a re-import is idempotent.
 * NON-CLINICAL: holds ONLY user-entered values + an OPTIONAL user-entered referenceText.
 */
data class LabMarkerRow(
    val id: String,
    val deviceId: String,
    val markerKey: String,
    val category: String,
    val day: String,
    val takenAt: Long,
    val value: Double? = null,
    val valueText: String? = null,
    val unit: String,
    val source: String,
    val note: String? = null,
    val referenceText: String? = null,
)

/**
 * Cached journal answer (logged behaviour). Swift `journal` (v8).
 * Natural key (deviceId, day, question) where day is "YYYY-MM-DD". answeredYes is stored as
 * INTEGER 0/1 in SQLite; exposed as Boolean here.
 */
data class JournalEntry(
    val deviceId: String,
    val day: String,
    val question: String,
    val answeredYes: Boolean,
    val notes: String? = null,
    val numericValue: Double? = null,
)

/**
 * Cached workout (Whoop + Apple Health). Swift `workout` (v8).
 * Natural key (deviceId, startTs, sport). All metric columns nullable. source distinguishes
 * origin ("my-whoop" / "apple-health"); zonesJSON is verbatim HR-zone-percentages JSON.
 */
data class WorkoutRow(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
    val sport: String,
    val source: String,
    val durationS: Double? = null,
    val energyKcal: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val strain: Double? = null,
    val distanceM: Double? = null,
    val zonesJSON: String? = null,
    val notes: String? = null,
    val routePolyline: String? = null,
)

/**
 * Durable "this detected bout is not a workout" marker (#107). PK (deviceId, startTs).
 * Android-only table (no GRDB twin): macOS persists the equivalent as a UserDefaults span list.
 */
data class DismissedWorkout(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
)

/**
 * Durable tombstone for a user-DELETED sleep session (#33). PK (deviceId, startTs).
 */
data class DismissedSleep(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
)

/**
 * Cached Apple-Health daily aggregate. Swift `appleDaily` (v8).
 * Natural key (deviceId, day) where day is "YYYY-MM-DD". All metric columns nullable.
 */
data class AppleDaily(
    val deviceId: String,
    val day: String,
    val steps: Int? = null,
    val activeKcal: Double? = null,
    val basalKcal: Double? = null,
    val vo2max: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val walkingHr: Int? = null,
    val weightKg: Double? = null,
)

/**
 * One Live Session (silent guardian) record (v22). Natural key (deviceId, startTs).
 * endTs is null while the session is still in progress. Twin of the Swift LiveSessionRow.
 */
data class LiveSessionRow(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long?,
    val chargeAtStart: Double?,
    val floorBpm: Double,
    val ceilingBpm: Double,
    val inBandSec: Double,
    val belowSec: Double,
    val aboveSec: Double,
    val pushCount: Int,
    val easeCount: Int,
    val hrSource: String,
)

// ---------------------------------------------------------------------------
// Device-registry schema (from PairedDevice.kt) — the desktop port of the
// Swift foundation in Packages/WhoopStore (PairedDevice.swift +
// DeviceRegistryStore.swift) and the Database.swift v15 pairedDevice /
// dayOwnership migration.
// ---------------------------------------------------------------------------

/**
 * A device the user has paired. PK = id (== deviceId in the sample tables).
 * Exactly one row is active at a time (invariant I1, enforced in DeviceRegistry.setActive).
 */
data class PairedDeviceRow(
    val id: String,
    val brand: String,
    val model: String,
    val nickname: String?,
    val peripheralId: String? = null,
    val sourceKind: String,
    val capabilities: String,
    val status: String,
    val addedAt: Long,
    val lastSeenAt: Long,
)

/**
 * Override of which device owns a given local day's displayed/scored metrics
 * (invariant I2: a day's scores are never blended across sources). PK = day ("YYYY-MM-DD").
 */
data class DayOwnershipRow(
    val day: String,
    val deviceId: String,
    val locked: Boolean = false,
)

/** Lifecycle of a paired device. Stored as the lowercase enum name (Swift DeviceStatus rawValue). */
enum class DeviceStatus { active, paired, archived }

/** How a device's data reaches the store. Stored as the enum name (Swift SourceKind rawValue). */
enum class SourceKind { liveBLE, historyBLE, cloudImport, fileImport, ftms, huami, oura }

/** A canonical metric a source can provide — drives capability-aware UI + the day-owner resolver.
 *  Stored as the enum name (Swift Metric rawValue) inside the comma-joined capabilities string. */
enum class Metric { hr, hrv, spo2, skinTemp, steps, sleep, strainLoad }
