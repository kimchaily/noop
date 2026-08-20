package com.noop.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * Data-access object for the local store, implemented with raw JDBC SQL — the desktop
 * replacement for Android's Room-generated [WhoopDao]. Mirrors every query method from
 * WhoopDao.kt + DeviceRegistryDao.kt.
 *
 * Stream inserts use `INSERT OR IGNORE` (== Room OnConflictStrategy.IGNORE == Swift
 * ON CONFLICT(...) DO NOTHING), idempotent by natural key.
 *
 * Server-derived caches (dailyMetric, sleepSession, metricSeries, etc.) use
 * `INSERT OR REPLACE` (== Room @Upsert), so the latest value wins on conflict.
 *
 * Range reads are ORDER BY ts ASC (R-R and events add a secondary key), bound by
 * [from, to] inclusive with a row limit.
 *
 * All parameterised queries use [PreparedStatement] to prevent SQL injection and to
 * reuse compiled query plans. The DAO is backed by a single [DesktopDatabase] connection;
 * every method is synchronised on the database's lock monitor for thread safety.
 */
class DesktopDao(private val db: DesktopDatabase) {

    private fun conn() = db.getConnection()

    // ------------------------------------------------------------------
    // MARK: - Device
    // ------------------------------------------------------------------

    /** Insert-or-replace a device by its id PK. */
    suspend fun upsertDevice(device: DeviceRow) {
        val sql = "INSERT OR REPLACE INTO `device` (`id`, `mac`, `name`, `firstSeen`, `lastSeen`) " +
            "VALUES (?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            ps.setString(1, device.id)
            ps.setString(2, device.mac)
            ps.setString(3, device.name)
            device.firstSeen?.let { ps.setLong(4, it) } ?: ps.setNull(4, java.sql.Types.INTEGER)
            device.lastSeen?.let { ps.setLong(5, it) } ?: ps.setNull(5, java.sql.Types.INTEGER)
            ps.executeUpdate()
        }
    }

    suspend fun device(id: String): DeviceRow? {
        conn().prepareStatement("SELECT * FROM `device` WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                return if (rs.next()) DeviceRow(
                    id = rs.getString("id"),
                    mac = rs.getString("mac"),
                    name = rs.getString("name"),
                    firstSeen = rs.getLong("firstSeen").takeIf { !rs.wasNull() },
                    lastSeen = rs.getLong("lastSeen").takeIf { !rs.wasNull() },
                ) else null
            }
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Device registry (pairedDevice / dayOwnership)
    // ------------------------------------------------------------------

    suspend fun pairedDevices(): List<PairedDeviceRow> {
        conn().createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM `pairedDevice` ORDER BY addedAt ASC").use { rs ->
                val out = ArrayList<PairedDeviceRow>()
                while (rs.next()) out.add(readPairedDevice(rs))
                return out
            }
        }
    }

    suspend fun activeDeviceId(): String? {
        conn().createStatement().use { stmt ->
            stmt.executeQuery("SELECT id FROM `pairedDevice` WHERE status = 'active' LIMIT 1").use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }

    suspend fun upsertPairedDevice(row: PairedDeviceRow) {
        val sql = "INSERT OR REPLACE INTO `pairedDevice` " +
            "(`id`, `brand`, `model`, `nickname`, `peripheralId`, `sourceKind`, `capabilities`, " +
            "`status`, `addedAt`, `lastSeenAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            ps.setString(1, row.id)
            ps.setString(2, row.brand)
            ps.setString(3, row.model)
            ps.setString(4, row.nickname)
            ps.setString(5, row.peripheralId)
            ps.setString(6, row.sourceKind)
            ps.setString(7, row.capabilities)
            ps.setString(8, row.status)
            ps.setLong(9, row.addedAt)
            ps.setLong(10, row.lastSeenAt)
            ps.executeUpdate()
        }
    }

    suspend fun demoteActive() {
        conn().createStatement().use { it.executeUpdate("UPDATE `pairedDevice` SET status = 'paired' WHERE status = 'active'") }
    }

    suspend fun promote(id: String, now: Long) {
        conn().prepareStatement("UPDATE `pairedDevice` SET status = 'active', lastSeenAt = ? WHERE id = ?").use { ps ->
            ps.setLong(1, now)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    suspend fun archiveDevice(id: String) {
        conn().prepareStatement("UPDATE `pairedDevice` SET status = 'archived' WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    suspend fun renameDevice(id: String, nickname: String?) {
        conn().prepareStatement("UPDATE `pairedDevice` SET nickname = ? WHERE id = ?").use { ps ->
            ps.setString(1, nickname)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    suspend fun setPeripheralId(id: String, peripheralId: String?) {
        conn().prepareStatement("UPDATE `pairedDevice` SET peripheralId = ? WHERE id = ?").use { ps ->
            ps.setString(1, peripheralId)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    suspend fun deviceForPeripheralId(peripheralId: String): PairedDeviceRow? {
        conn().prepareStatement("SELECT * FROM `pairedDevice` WHERE peripheralId = ? LIMIT 1").use { ps ->
            ps.setString(1, peripheralId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) readPairedDevice(rs) else null
            }
        }
    }

    // deleteAllData — clear one device's recordings across every deviceId-keyed table

    suspend fun deleteHrFor(deviceId: String) = deleteByDeviceId("hrSample", deviceId)
    suspend fun deleteRrFor(deviceId: String) = deleteByDeviceId("rrInterval", deviceId)
    suspend fun deleteSpo2For(deviceId: String) = deleteByDeviceId("spo2Sample", deviceId)
    suspend fun deleteSkinTempFor(deviceId: String) = deleteByDeviceId("skinTempSample", deviceId)
    suspend fun deleteRespFor(deviceId: String) = deleteByDeviceId("respSample", deviceId)
    suspend fun deleteGravityFor(deviceId: String) = deleteByDeviceId("gravitySample", deviceId)
    suspend fun deleteStepsFor(deviceId: String) = deleteByDeviceId("stepSample", deviceId)
    suspend fun deletePpgHrFor(deviceId: String) = deleteByDeviceId("ppgHrSample", deviceId)
    suspend fun deleteEventsFor(deviceId: String) = deleteByDeviceId("event", deviceId)
    suspend fun deleteBatteryFor(deviceId: String) = deleteByDeviceId("battery", deviceId)
    suspend fun deleteDailyMetricsFor(deviceId: String) = deleteByDeviceId("dailyMetric", deviceId)
    suspend fun deleteSleepSessionsFor(deviceId: String) = deleteByDeviceId("sleepSession", deviceId)
    suspend fun deleteJournalFor(deviceId: String) = deleteByDeviceId("journal", deviceId)
    suspend fun deleteWorkoutsFor(deviceId: String) = deleteByDeviceId("workout", deviceId)
    suspend fun deleteAppleDailyFor(deviceId: String) = deleteByDeviceId("appleDaily", deviceId)
    suspend fun deleteMetricSeriesFor(deviceId: String) = deleteByDeviceId("metricSeries", deviceId)
    suspend fun deleteDayOwnershipFor(deviceId: String) = deleteByDeviceId("dayOwnership", deviceId)

    private fun deleteByDeviceId(table: String, deviceId: String) {
        conn().prepareStatement("DELETE FROM `$table` WHERE deviceId = ?").use { ps ->
            ps.setString(1, deviceId)
            ps.executeUpdate()
        }
    }

    suspend fun setDayOwner(row: DayOwnershipRow) {
        conn().prepareStatement(
            "INSERT OR REPLACE INTO `dayOwnership` (`day`, `deviceId`, `locked`) VALUES (?, ?, ?)"
        ).use { ps ->
            ps.setString(1, row.day)
            ps.setString(2, row.deviceId)
            ps.setInt(3, if (row.locked) 1 else 0)
            ps.executeUpdate()
        }
    }

    suspend fun dayOwner(day: String): DayOwnershipRow? {
        conn().prepareStatement("SELECT * FROM `dayOwnership` WHERE day = ?").use { ps ->
            ps.setString(1, day)
            ps.executeQuery().use { rs ->
                return if (rs.next()) DayOwnershipRow(
                    day = rs.getString("day"),
                    deviceId = rs.getString("deviceId"),
                    locked = rs.getInt("locked") != 0,
                ) else null
            }
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Stream inserts (idempotent by natural key, INSERT OR IGNORE)
    // ------------------------------------------------------------------

    suspend fun insertHr(rows: List<HrSample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `hrSample` (`deviceId`, `ts`, `bpm`, `synced`) VALUES (?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.bpm); ps.setInt(4, r.synced) }

    suspend fun insertRr(rows: List<RrInterval>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `rrInterval` (`deviceId`, `ts`, `rrMs`, `synced`) VALUES (?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.rrMs); ps.setInt(4, r.synced) }

    suspend fun insertEvents(rows: List<EventRow>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `event` (`deviceId`, `ts`, `kind`, `payloadJSON`, `synced`) VALUES (?, ?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setString(3, r.kind); ps.setString(4, r.payloadJSON); ps.setInt(5, r.synced) }

    suspend fun insertBattery(rows: List<BatterySample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `battery` (`deviceId`, `ts`, `soc`, `mv`, `charging`, `synced`) VALUES (?, ?, ?, ?, ?, ?)"
        ) { ps, r ->
            ps.setString(1, r.deviceId); ps.setLong(2, r.ts)
            r.soc?.let { ps.setDouble(3, it) } ?: ps.setNull(3, java.sql.Types.REAL)
            r.mv?.let { ps.setInt(4, it) } ?: ps.setNull(4, java.sql.Types.INTEGER)
            r.charging?.let { ps.setInt(5, if (it) 1 else 0) } ?: ps.setNull(5, java.sql.Types.INTEGER)
            ps.setInt(6, r.synced)
        }

    suspend fun insertSpo2(rows: List<Spo2Sample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `spo2Sample` (`deviceId`, `ts`, `red`, `ir`, `synced`) VALUES (?, ?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.red); ps.setInt(4, r.ir); ps.setInt(5, r.synced) }

    suspend fun insertSkinTemp(rows: List<SkinTempSample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `skinTempSample` (`deviceId`, `ts`, `raw`, `synced`) VALUES (?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.raw); ps.setInt(4, r.synced) }

    suspend fun insertSteps(rows: List<StepSample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `stepSample` (`deviceId`, `ts`, `counter`, `synced`, `activityClass`) VALUES (?, ?, ?, ?, ?)"
        ) { ps, r ->
            ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.counter); ps.setInt(4, r.synced)
            r.activityClass?.let { ps.setInt(5, it) } ?: ps.setNull(5, java.sql.Types.INTEGER)
        }

    suspend fun insertSleepState(rows: List<SleepStateSampleEntity>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `sleepStateSample` (`deviceId`, `ts`, `state`) VALUES (?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.state) }

    suspend fun insertResp(rows: List<RespSample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `respSample` (`deviceId`, `ts`, `raw`, `synced`) VALUES (?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.raw); ps.setInt(4, r.synced) }

    suspend fun insertGravity(rows: List<GravitySample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `gravitySample` (`deviceId`, `ts`, `x`, `y`, `z`, `synced`) VALUES (?, ?, ?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setDouble(3, r.x); ps.setDouble(4, r.y); ps.setDouble(5, r.z); ps.setInt(6, r.synced) }

    suspend fun insertPpgHr(rows: List<PpgHrSample>): List<Long> =
        batchInsertIgnore(rows,
            "INSERT OR IGNORE INTO `ppgHrSample` (`deviceId`, `ts`, `bpm`, `conf`, `synced`) VALUES (?, ?, ?, ?, ?)"
        ) { ps, r -> ps.setString(1, r.deviceId); ps.setLong(2, r.ts); ps.setInt(3, r.bpm); ps.setDouble(4, r.conf); ps.setInt(5, r.synced) }

    suspend fun insertDismissed(rows: List<DismissedWorkout>) {
        if (rows.isEmpty()) return
        conn().prepareStatement(
            "INSERT OR IGNORE INTO `dismissedWorkout` (`deviceId`, `startTs`, `endTs`) VALUES (?, ?, ?)"
        ).use { ps ->
            for (r in rows) { ps.setString(1, r.deviceId); ps.setLong(2, r.startTs); ps.setLong(3, r.endTs); ps.addBatch() }
            ps.executeBatch()
        }
    }

    suspend fun insertDismissedSleep(rows: List<DismissedSleep>) {
        if (rows.isEmpty()) return
        conn().prepareStatement(
            "INSERT OR IGNORE INTO `dismissedSleep` (`deviceId`, `startTs`, `endTs`) VALUES (?, ?, ?)"
        ).use { ps ->
            for (r in rows) { ps.setString(1, r.deviceId); ps.setLong(2, r.startTs); ps.setLong(3, r.endTs); ps.addBatch() }
            ps.executeBatch()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Server-derived caches (latest value wins, INSERT OR REPLACE)
    // ------------------------------------------------------------------

    suspend fun upsertLiveSession(row: LiveSessionRow) {
        val sql = "INSERT OR REPLACE INTO `liveSession` " +
            "(`deviceId`, `startTs`, `endTs`, `chargeAtStart`, `floorBpm`, `ceilingBpm`, " +
            "`inBandSec`, `belowSec`, `aboveSec`, `pushCount`, `easeCount`, `hrSource`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            ps.setString(1, row.deviceId); ps.setLong(2, row.startTs)
            row.endTs?.let { ps.setLong(3, it) } ?: ps.setNull(3, java.sql.Types.INTEGER)
            row.chargeAtStart?.let { ps.setDouble(4, it) } ?: ps.setNull(4, java.sql.Types.REAL)
            ps.setDouble(5, row.floorBpm); ps.setDouble(6, row.ceilingBpm)
            ps.setDouble(7, row.inBandSec); ps.setDouble(8, row.belowSec); ps.setDouble(9, row.aboveSec)
            ps.setInt(10, row.pushCount); ps.setInt(11, row.easeCount); ps.setString(12, row.hrSource)
            ps.executeUpdate()
        }
    }

    suspend fun upsertDailyMetrics(rows: List<DailyMetric>) {
        if (rows.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `dailyMetric` " +
            "(`deviceId`, `day`, `totalSleepMin`, `efficiency`, `deepMin`, `remMin`, `lightMin`, " +
            "`disturbances`, `restingHr`, `avgHrv`, `recovery`, `strain`, `exerciseCount`, " +
            "`spo2Pct`, `skinTempDevC`, `respRateBpm`, `steps`, `activeKcalEst`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            for (d in rows) {
                ps.setString(1, d.deviceId); ps.setString(2, d.day)
                setNullableDouble(ps, 3, d.totalSleepMin)
                setNullableDouble(ps, 4, d.efficiency)
                setNullableDouble(ps, 5, d.deepMin)
                setNullableDouble(ps, 6, d.remMin)
                setNullableDouble(ps, 7, d.lightMin)
                setNullableInt(ps, 8, d.disturbances)
                setNullableInt(ps, 9, d.restingHr)
                setNullableDouble(ps, 10, d.avgHrv)
                setNullableDouble(ps, 11, d.recovery)
                setNullableDouble(ps, 12, d.strain)
                setNullableInt(ps, 13, d.exerciseCount)
                setNullableDouble(ps, 14, d.spo2Pct)
                setNullableDouble(ps, 15, d.skinTempDevC)
                setNullableDouble(ps, 16, d.respRateBpm)
                setNullableInt(ps, 17, d.steps)
                setNullableDouble(ps, 18, d.activeKcalEst)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun upsertSleepSessions(rows: List<SleepSession>) {
        if (rows.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `sleepSession` " +
            "(`deviceId`, `startTs`, `endTs`, `efficiency`, `restingHr`, `avgHrv`, `stagesJSON`, " +
            "`userEdited`, `startTsAdjusted`, `motionJSON`, `sleepStateJSON`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            for (s in rows) {
                ps.setString(1, s.deviceId); ps.setLong(2, s.startTs); ps.setLong(3, s.endTs)
                setNullableDouble(ps, 4, s.efficiency)
                setNullableInt(ps, 5, s.restingHr)
                setNullableDouble(ps, 6, s.avgHrv)
                ps.setString(7, s.stagesJSON)
                ps.setInt(8, if (s.userEdited) 1 else 0)
                setNullableLong(ps, 9, s.startTsAdjusted)
                ps.setString(10, s.motionJSON)
                ps.setString(11, s.sleepStateJSON)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun deleteSleepSession(deviceId: String, startTs: Long) {
        conn().prepareStatement("DELETE FROM `sleepSession` WHERE deviceId = ? AND startTs = ?").use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, startTs); ps.executeUpdate()
        }
    }

    /** Idempotent additive insert (IGNORE). Returns the inserted rowid, or -1 on conflict. */
    suspend fun insertSleepSession(row: SleepSession): Long {
        val sql = "INSERT OR IGNORE INTO `sleepSession` " +
            "(`deviceId`, `startTs`, `endTs`, `efficiency`, `restingHr`, `avgHrv`, `stagesJSON`, " +
            "`userEdited`, `startTsAdjusted`, `motionJSON`, `sleepStateJSON`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setString(1, row.deviceId); ps.setLong(2, row.startTs); ps.setLong(3, row.endTs)
            setNullableDouble(ps, 4, row.efficiency)
            setNullableInt(ps, 5, row.restingHr)
            setNullableDouble(ps, 6, row.avgHrv)
            ps.setString(7, row.stagesJSON)
            ps.setInt(8, if (row.userEdited) 1 else 0)
            setNullableLong(ps, 9, row.startTsAdjusted)
            ps.setString(10, row.motionJSON)
            ps.setString(11, row.sleepStateJSON)
            val n = ps.executeUpdate()
            if (n <= 0) return -1L
            ps.generatedKeys.use { rs -> return if (rs.next()) rs.getLong(1) else -1L }
        }
    }

    suspend fun updateSleepStages(deviceId: String, detectedStartTs: Long, stagesJSON: String): Int {
        conn().prepareStatement(
            "UPDATE `sleepSession` SET stagesJSON = ? WHERE deviceId = ? AND startTs = ? AND userEdited = 1"
        ).use { ps ->
            ps.setString(1, stagesJSON); ps.setString(2, deviceId); ps.setLong(3, detectedStartTs)
            return ps.executeUpdate()
        }
    }

    suspend fun updateSessionMotion(deviceId: String, sessionStart: Long, json: String?): Int {
        conn().prepareStatement(
            "UPDATE `sleepSession` SET motionJSON = ? WHERE deviceId = ? AND startTs = ?"
        ).use { ps ->
            ps.setString(1, json); ps.setString(2, deviceId); ps.setLong(3, sessionStart)
            return ps.executeUpdate()
        }
    }

    suspend fun sessionMotionJson(deviceId: String, sessionStart: Long): String? {
        conn().prepareStatement(
            "SELECT motionJSON FROM `sleepSession` WHERE deviceId = ? AND startTs = ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, sessionStart)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    suspend fun updateSessionSleepState(deviceId: String, sessionStart: Long, json: String?): Int {
        conn().prepareStatement(
            "UPDATE `sleepSession` SET sleepStateJSON = ? WHERE deviceId = ? AND startTs = ?"
        ).use { ps ->
            ps.setString(1, json); ps.setString(2, deviceId); ps.setLong(3, sessionStart)
            return ps.executeUpdate()
        }
    }

    suspend fun sessionSleepStateJson(deviceId: String, sessionStart: Long): String? {
        conn().prepareStatement(
            "SELECT sleepStateJSON FROM `sleepSession` WHERE deviceId = ? AND startTs = ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, sessionStart)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    suspend fun upsertMetricSeries(rows: List<MetricSeriesRow>) {
        if (rows.isEmpty()) return
        conn().prepareStatement(
            "INSERT OR REPLACE INTO `metricSeries` (`deviceId`, `day`, `key`, `value`) VALUES (?, ?, ?, ?)"
        ).use { ps ->
            for (r in rows) {
                ps.setString(1, r.deviceId); ps.setString(2, r.day); ps.setString(3, r.key); ps.setDouble(4, r.value)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun upsertJournal(rows: List<JournalEntry>) {
        if (rows.isEmpty()) return
        conn().prepareStatement(
            "INSERT OR REPLACE INTO `journal` (`deviceId`, `day`, `question`, `answeredYes`, `notes`, `numericValue`) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            for (j in rows) {
                ps.setString(1, j.deviceId); ps.setString(2, j.day); ps.setString(3, j.question)
                ps.setInt(4, if (j.answeredYes) 1 else 0)
                ps.setString(5, j.notes)
                setNullableDouble(ps, 6, j.numericValue)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun upsertWorkouts(rows: List<WorkoutRow>) {
        if (rows.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `workout` " +
            "(`deviceId`, `startTs`, `endTs`, `sport`, `source`, `durationS`, `energyKcal`, " +
            "`avgHr`, `maxHr`, `strain`, `distanceM`, `zonesJSON`, `notes`, `routePolyline`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            for (w in rows) {
                ps.setString(1, w.deviceId); ps.setLong(2, w.startTs); ps.setLong(3, w.endTs)
                ps.setString(4, w.sport); ps.setString(5, w.source)
                setNullableDouble(ps, 6, w.durationS)
                setNullableDouble(ps, 7, w.energyKcal)
                setNullableInt(ps, 8, w.avgHr)
                setNullableInt(ps, 9, w.maxHr)
                setNullableDouble(ps, 10, w.strain)
                setNullableDouble(ps, 11, w.distanceM)
                ps.setString(12, w.zonesJSON)
                ps.setString(13, w.notes)
                ps.setString(14, w.routePolyline)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun upsertAppleDaily(rows: List<AppleDaily>) {
        if (rows.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `appleDaily` " +
            "(`deviceId`, `day`, `steps`, `activeKcal`, `basalKcal`, `vo2max`, `avgHr`, `maxHr`, `walkingHr`, `weightKg`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            for (a in rows) {
                ps.setString(1, a.deviceId); ps.setString(2, a.day)
                setNullableInt(ps, 3, a.steps)
                setNullableDouble(ps, 4, a.activeKcal)
                setNullableDouble(ps, 5, a.basalKcal)
                setNullableDouble(ps, 6, a.vo2max)
                setNullableInt(ps, 7, a.avgHr)
                setNullableInt(ps, 8, a.maxHr)
                setNullableInt(ps, 9, a.walkingHr)
                setNullableDouble(ps, 10, a.weightKg)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Range reads (ORDER BY ts ASC, inclusive [from, to], limited)
    // ------------------------------------------------------------------

    suspend fun hrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<HrSample> {
        val sql = "SELECT deviceId, ts, bpm, synced FROM (" +
            "SELECT deviceId, ts, bpm, synced FROM `hrSample` " +
            "WHERE deviceId = ? AND ts >= ? AND ts <= ? " +
            "UNION ALL " +
            "SELECT p.deviceId AS deviceId, p.ts AS ts, p.bpm AS bpm, 0 AS synced FROM `ppgHrSample` p " +
            "WHERE p.deviceId = ? AND p.ts >= ? AND p.ts <= ? " +
            "AND NOT EXISTS (SELECT 1 FROM `hrSample` h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)" +
            ") ORDER BY ts ASC LIMIT ?"
        conn().prepareStatement(sql).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
            ps.setString(4, deviceId); ps.setLong(5, from); ps.setLong(6, to)
            ps.setInt(7, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<HrSample>()
                while (rs.next()) out.add(HrSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("bpm"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun rawHrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<HrSample> {
        conn().prepareStatement(
            "SELECT * FROM `hrSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<HrSample>()
                while (rs.next()) out.add(HrSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("bpm"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun hrBuckets(deviceId: String, from: Long, to: Long, bucketSeconds: Long): List<HrBucket> {
        val sql = "SELECT (ts / ?) * ? AS bucket, AVG(bpm) AS avgBpm FROM (" +
            "SELECT ts, bpm FROM `hrSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? " +
            "UNION ALL " +
            "SELECT p.ts AS ts, p.bpm AS bpm FROM `ppgHrSample` p " +
            "WHERE p.deviceId = ? AND p.ts >= ? AND p.ts <= ? " +
            "AND NOT EXISTS (SELECT 1 FROM `hrSample` h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)" +
            ") GROUP BY ts / ? ORDER BY bucket ASC"
        conn().prepareStatement(sql).use { ps ->
            ps.setLong(1, bucketSeconds); ps.setLong(2, bucketSeconds)
            ps.setString(3, deviceId); ps.setLong(4, from); ps.setLong(5, to)
            ps.setString(6, deviceId); ps.setLong(7, from); ps.setLong(8, to)
            ps.setLong(9, bucketSeconds)
            ps.executeQuery().use { rs ->
                val out = ArrayList<HrBucket>()
                while (rs.next()) out.add(HrBucket(rs.getLong("bucket"), rs.getDouble("avgBpm")))
                return out
            }
        }
    }

    suspend fun ppgHrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<PpgHrSample> {
        conn().prepareStatement(
            "SELECT * FROM `ppgHrSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<PpgHrSample>()
                while (rs.next()) out.add(PpgHrSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("bpm"), rs.getDouble("conf"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun hrWindowStats(deviceId: String, from: Long, to: Long): HrWindowStats {
        conn().prepareStatement(
            "SELECT COUNT(*) AS n, AVG(bpm) AS avg, MAX(bpm) AS max FROM `hrSample` WHERE deviceId = ? AND ts >= ? AND ts <= ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val n = rs.getLong("n")
                    val avg = rs.getDouble("avg"); val avgVal = if (rs.wasNull()) null else avg
                    val max = rs.getInt("max"); val maxVal = if (rs.wasNull()) null else max
                    return HrWindowStats(n, avgVal, maxVal)
                }
                return HrWindowStats(0, null, null)
            }
        }
    }

    suspend fun rrIntervals(deviceId: String, from: Long, to: Long, limit: Int): List<RrInterval> {
        conn().prepareStatement(
            "SELECT * FROM `rrInterval` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC, rrMs ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<RrInterval>()
                while (rs.next()) out.add(RrInterval(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("rrMs"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun events(deviceId: String, from: Long, to: Long, limit: Int): List<EventRow> {
        conn().prepareStatement(
            "SELECT * FROM `event` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC, kind ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<EventRow>()
                while (rs.next()) out.add(EventRow(rs.getString("deviceId"), rs.getLong("ts"), rs.getString("kind"), rs.getString("payloadJSON"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun batterySamples(deviceId: String, from: Long, to: Long, limit: Int): List<BatterySample> {
        conn().prepareStatement(
            "SELECT * FROM `battery` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<BatterySample>()
                while (rs.next()) out.add(readBattery(rs))
                return out
            }
        }
    }

    suspend fun spo2Samples(deviceId: String, from: Long, to: Long, limit: Int): List<Spo2Sample> {
        conn().prepareStatement(
            "SELECT * FROM `spo2Sample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<Spo2Sample>()
                while (rs.next()) out.add(Spo2Sample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("red"), rs.getInt("ir"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun skinTempSamples(deviceId: String, from: Long, to: Long, limit: Int): List<SkinTempSample> {
        conn().prepareStatement(
            "SELECT * FROM `skinTempSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<SkinTempSample>()
                while (rs.next()) out.add(SkinTempSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("raw"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun stepSamples(deviceId: String, from: Long, to: Long, limit: Int): List<StepSample> {
        conn().prepareStatement(
            "SELECT * FROM `stepSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<StepSample>()
                while (rs.next()) {
                    val ac = rs.getInt("activityClass")
                    out.add(StepSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("counter"), if (rs.wasNull()) null else ac, rs.getInt("synced")))
                }
                return out
            }
        }
    }

    suspend fun sleepStateSamples(deviceId: String, from: Long, to: Long, limit: Int): List<SleepStateSampleEntity> {
        conn().prepareStatement(
            "SELECT * FROM `sleepStateSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<SleepStateSampleEntity>()
                while (rs.next()) out.add(SleepStateSampleEntity(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("state")))
                return out
            }
        }
    }

    suspend fun respSamples(deviceId: String, from: Long, to: Long, limit: Int): List<RespSample> {
        conn().prepareStatement(
            "SELECT * FROM `respSample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<RespSample>()
                while (rs.next()) out.add(RespSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("raw"), rs.getInt("synced")))
                return out
            }
        }
    }

    suspend fun gravitySamples(deviceId: String, from: Long, to: Long, limit: Int): List<GravitySample> {
        conn().prepareStatement(
            "SELECT * FROM `gravitySample` WHERE deviceId = ? AND ts >= ? AND ts <= ? ORDER BY ts ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<GravitySample>()
                while (rs.next()) out.add(GravitySample(rs.getString("deviceId"), rs.getLong("ts"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getInt("synced")))
                return out
            }
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Daily metrics / sleep reads
    // ------------------------------------------------------------------

    suspend fun dailyMetricsRange(deviceId: String, from: String, to: String): List<DailyMetric> {
        conn().prepareStatement(
            "SELECT * FROM `dailyMetric` WHERE deviceId = ? AND day >= ? AND day <= ? ORDER BY day ASC"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, from); ps.setString(3, to)
            ps.executeQuery().use { rs ->
                val out = ArrayList<DailyMetric>()
                while (rs.next()) out.add(readDailyMetric(rs))
                return out
            }
        }
    }

    suspend fun deleteDailyMetricsInRange(deviceId: String, from: String, to: String) {
        conn().prepareStatement("DELETE FROM `dailyMetric` WHERE deviceId = ? AND day >= ? AND day <= ?").use { ps ->
            ps.setString(1, deviceId); ps.setString(2, from); ps.setString(3, to); ps.executeUpdate()
        }
    }

    suspend fun days(deviceId: String): List<DailyMetric> {
        conn().prepareStatement("SELECT * FROM `dailyMetric` WHERE deviceId = ? ORDER BY day ASC").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<DailyMetric>()
                while (rs.next()) out.add(readDailyMetric(rs))
                return out
            }
        }
    }

    fun daysFlow(deviceId: String): Flow<List<DailyMetric>> = flow {
        emit(days(deviceId))
    }

    fun recentDaysFlow(deviceId: String, limit: Int): Flow<List<DailyMetric>> = flow {
        conn().prepareStatement(
            "SELECT * FROM `dailyMetric` WHERE deviceId = ? ORDER BY day DESC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<DailyMetric>()
                while (rs.next()) out.add(readDailyMetric(rs))
                emit(out)
            }
        }
    }

    suspend fun sleepSessions(deviceId: String, from: Long, to: Long, limit: Int): List<SleepSession> {
        conn().prepareStatement(
            "SELECT * FROM `sleepSession` WHERE deviceId = ? AND startTs >= ? AND startTs <= ? ORDER BY startTs ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<SleepSession>()
                while (rs.next()) out.add(readSleepSession(rs))
                return out
            }
        }
    }

    suspend fun editedSleepSessions(deviceId: String): List<SleepSession> {
        conn().prepareStatement(
            "SELECT * FROM `sleepSession` WHERE deviceId = ? AND userEdited = 1 ORDER BY startTs ASC"
        ).use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<SleepSession>()
                while (rs.next()) out.add(readSleepSession(rs))
                return out
            }
        }
    }

    fun editedSleepSessionsFlow(deviceId: String): Flow<List<SleepSession>> = flow {
        emit(editedSleepSessions(deviceId))
    }

    // ------------------------------------------------------------------
    // MARK: - Generic metric series (Swift metricSeries, v9)
    // ------------------------------------------------------------------

    suspend fun metricSeries(deviceId: String, key: String, from: String, to: String): List<MetricSeriesRow> {
        conn().prepareStatement(
            "SELECT * FROM `metricSeries` WHERE deviceId = ? AND key = ? AND day >= ? AND day <= ? ORDER BY day ASC"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, key); ps.setString(3, from); ps.setString(4, to)
            ps.executeQuery().use { rs ->
                val out = ArrayList<MetricSeriesRow>()
                while (rs.next()) out.add(MetricSeriesRow(rs.getString("deviceId"), rs.getString("day"), rs.getString("key"), rs.getDouble("value")))
                return out
            }
        }
    }

    suspend fun metricKeys(deviceId: String): List<String> {
        conn().prepareStatement("SELECT DISTINCT key FROM `metricSeries` WHERE deviceId = ? ORDER BY key ASC").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    suspend fun deleteMetricSeriesPoint(deviceId: String, day: String, key: String) {
        conn().prepareStatement("DELETE FROM `metricSeries` WHERE deviceId = ? AND day = ? AND key = ?").use { ps ->
            ps.setString(1, deviceId); ps.setString(2, day); ps.setString(3, key); ps.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Lab Book markers (Swift labMarker, v17)
    // ------------------------------------------------------------------

    suspend fun insertLabMarkersRaw(rows: List<LabMarkerRow>) {
        if (rows.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `labMarker` " +
            "(`id`, `deviceId`, `markerKey`, `category`, `day`, `takenAt`, `value`, `valueText`, " +
            "`unit`, `source`, `note`, `referenceText`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        conn().prepareStatement(sql).use { ps ->
            for (r in rows) {
                ps.setString(1, r.id); ps.setString(2, r.deviceId); ps.setString(3, r.markerKey)
                ps.setString(4, r.category); ps.setString(5, r.day); ps.setLong(6, r.takenAt)
                setNullableDouble(ps, 7, r.value)
                ps.setString(8, r.valueText)
                ps.setString(9, r.unit); ps.setString(10, r.source)
                ps.setString(11, r.note); ps.setString(12, r.referenceText)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun labMarkersByCategory(deviceId: String, category: String): List<LabMarkerRow> {
        conn().prepareStatement(
            "SELECT * FROM `labMarker` WHERE deviceId = ? AND category = ? ORDER BY takenAt ASC"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, category)
            ps.executeQuery().use { rs ->
                val out = ArrayList<LabMarkerRow>()
                while (rs.next()) out.add(readLabMarker(rs))
                return out
            }
        }
    }

    suspend fun labMarkersByKey(deviceId: String, markerKey: String): List<LabMarkerRow> {
        conn().prepareStatement(
            "SELECT * FROM `labMarker` WHERE deviceId = ? AND markerKey = ? ORDER BY takenAt ASC"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, markerKey)
            ps.executeQuery().use { rs ->
                val out = ArrayList<LabMarkerRow>()
                while (rs.next()) out.add(readLabMarker(rs))
                return out
            }
        }
    }

    suspend fun markerKeysPresent(deviceId: String): List<String> {
        conn().prepareStatement("SELECT DISTINCT markerKey FROM `labMarker` WHERE deviceId = ? ORDER BY markerKey ASC").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    suspend fun labMarkerById(id: String): LabMarkerRow? {
        conn().prepareStatement("SELECT * FROM `labMarker` WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> return if (rs.next()) readLabMarker(rs) else null }
        }
    }

    suspend fun latestNumericForCell(deviceId: String, markerKey: String, day: String): Double? {
        conn().prepareStatement(
            "SELECT value FROM `labMarker` WHERE deviceId = ? AND markerKey = ? AND day = ? AND value IS NOT NULL ORDER BY takenAt DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, markerKey); ps.setString(3, day)
            ps.executeQuery().use { rs ->
                return if (rs.next()) { val v = rs.getDouble(1); if (rs.wasNull()) null else v } else null
            }
        }
    }

    suspend fun deleteLabMarkerRaw(id: String) {
        conn().prepareStatement("DELETE FROM `labMarker` WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    /** Upsert marker rows, then re-project each affected cell into metricSeries. Atomic. */
    suspend fun upsertLabMarkers(rows: List<LabMarkerRow>) {
        if (rows.isEmpty()) return
        db.withTransaction {
            kotlinx.coroutines.runBlocking {
                insertLabMarkersRaw(rows)
                val cells = rows.map { Triple(it.deviceId, it.markerKey, it.day) }.toSet()
                for ((deviceId, markerKey, day) in cells) {
                    reprojectCell(deviceId, markerKey, day)
                }
            }
        }
    }

    /** Delete one reading by id; re-project its cell. Returns true if a row was deleted. */
    suspend fun deleteLabMarker(id: String): Boolean {
        return db.withTransaction {
            kotlinx.coroutines.runBlocking {
                val row = labMarkerById(id) ?: return@runBlocking false
                deleteLabMarkerRaw(id)
                reprojectCell(row.deviceId, row.markerKey, row.day)
                true
            }
        }
    }

    /** Recompute the metricSeries projection for one cell: latest-numeric-per-day wins. */
    suspend fun reprojectCell(deviceId: String, markerKey: String, day: String) {
        val latest = latestNumericForCell(deviceId, markerKey, day)
        if (latest != null) {
            upsertMetricSeries(listOf(MetricSeriesRow(LAB_BOOK_SOURCE_ID, day, markerKey, latest)))
        } else {
            deleteMetricSeriesPoint(LAB_BOOK_SOURCE_ID, day, markerKey)
        }
    }

    // ------------------------------------------------------------------
    // MARK: - #34 refile
    // ------------------------------------------------------------------

    suspend fun metricSeriesCount(deviceId: String): Int {
        conn().prepareStatement("SELECT COUNT(*) FROM `metricSeries` WHERE deviceId = ?").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    suspend fun reassignAppleDaily(from: String, to: String) {
        conn().prepareStatement("UPDATE `appleDaily` SET deviceId = ? WHERE deviceId = ?").use { ps ->
            ps.setString(1, to); ps.setString(2, from); ps.executeUpdate()
        }
    }

    suspend fun reassignWorkoutsBySource(from: String, to: String, source: String) {
        conn().prepareStatement("UPDATE `workout` SET deviceId = ? WHERE deviceId = ? AND source = ?").use { ps ->
            ps.setString(1, to); ps.setString(2, from); ps.setString(3, source); ps.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Journal / workouts / Apple-Health reads
    // ------------------------------------------------------------------

    suspend fun journal(deviceId: String, from: String, to: String): List<JournalEntry> {
        conn().prepareStatement(
            "SELECT * FROM `journal` WHERE deviceId = ? AND day >= ? AND day <= ? ORDER BY day ASC, question ASC"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, from); ps.setString(3, to)
            ps.executeQuery().use { rs ->
                val out = ArrayList<JournalEntry>()
                while (rs.next()) {
                    val nv = rs.getDouble("numericValue")
                    out.add(JournalEntry(rs.getString("deviceId"), rs.getString("day"), rs.getString("question"),
                        rs.getInt("answeredYes") != 0, rs.getString("notes"), if (rs.wasNull()) null else nv))
                }
                return out
            }
        }
    }

    suspend fun deleteJournalEntry(deviceId: String, day: String, question: String) {
        conn().prepareStatement("DELETE FROM `journal` WHERE deviceId = ? AND day = ? AND question = ?").use { ps ->
            ps.setString(1, deviceId); ps.setString(2, day); ps.setString(3, question); ps.executeUpdate()
        }
    }

    suspend fun workouts(deviceId: String, from: Long, to: Long, limit: Int): List<WorkoutRow> {
        conn().prepareStatement(
            "SELECT * FROM `workout` WHERE deviceId = ? AND startTs >= ? AND startTs <= ? ORDER BY startTs ASC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, from); ps.setLong(3, to); ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<WorkoutRow>()
                while (rs.next()) out.add(readWorkout(rs))
                return out
            }
        }
    }

    suspend fun appleDaily(deviceId: String, from: String, to: String): List<AppleDaily> {
        conn().prepareStatement(
            "SELECT * FROM `appleDaily` WHERE deviceId = ? AND day >= ? AND day <= ? ORDER BY day ASC"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, from); ps.setString(3, to)
            ps.executeQuery().use { rs ->
                val out = ArrayList<AppleDaily>()
                while (rs.next()) out.add(readAppleDaily(rs))
                return out
            }
        }
    }

    suspend fun deleteWorkoutsBySport(deviceId: String, sport: String, from: Long, to: Long) {
        conn().prepareStatement(
            "DELETE FROM `workout` WHERE deviceId = ? AND sport = ? AND startTs >= ? AND startTs <= ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, sport); ps.setLong(3, from); ps.setLong(4, to); ps.executeUpdate()
        }
    }

    suspend fun deleteWorkoutByKey(deviceId: String, startTs: Long, sport: String) {
        conn().prepareStatement(
            "DELETE FROM `workout` WHERE deviceId = ? AND startTs = ? AND sport = ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, startTs); ps.setString(3, sport); ps.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Dismissed detected bouts / dismissed sleep
    // ------------------------------------------------------------------

    suspend fun dismissedWorkouts(deviceId: String): List<DismissedWorkout> {
        conn().prepareStatement("SELECT * FROM `dismissedWorkout` WHERE deviceId = ?").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<DismissedWorkout>()
                while (rs.next()) out.add(DismissedWorkout(rs.getString("deviceId"), rs.getLong("startTs"), rs.getLong("endTs")))
                return out
            }
        }
    }

    suspend fun dismissedSleeps(deviceId: String): List<DismissedSleep> {
        conn().prepareStatement("SELECT * FROM `dismissedSleep` WHERE deviceId = ?").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                val out = ArrayList<DismissedSleep>()
                while (rs.next()) out.add(DismissedSleep(rs.getString("deviceId"), rs.getLong("startTs"), rs.getLong("endTs")))
                return out
            }
        }
    }

    suspend fun deleteDismissedSleep(deviceId: String, startTs: Long) {
        conn().prepareStatement("DELETE FROM `dismissedSleep` WHERE deviceId = ? AND startTs = ?").use { ps ->
            ps.setString(1, deviceId); ps.setLong(2, startTs); ps.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Live Sessions
    // ------------------------------------------------------------------

    suspend fun recentLiveSessions(deviceId: String, limit: Int): List<LiveSessionRow> {
        conn().prepareStatement(
            "SELECT * FROM `liveSession` WHERE deviceId = ? ORDER BY startTs DESC LIMIT ?"
        ).use { ps ->
            ps.setString(1, deviceId); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val out = ArrayList<LiveSessionRow>()
                while (rs.next()) out.add(readLiveSession(rs))
                return out
            }
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Frontier / stats
    // ------------------------------------------------------------------

    suspend fun latestHrSampleTs(deviceId: String): Long? {
        val sql = "SELECT MAX(ts) FROM (" +
            "SELECT ts FROM `hrSample` WHERE deviceId = ? " +
            "UNION ALL " +
            "SELECT ts FROM `ppgHrSample` WHERE deviceId = ?)"
        conn().prepareStatement(sql).use { ps ->
            ps.setString(1, deviceId); ps.setString(2, deviceId)
            ps.executeQuery().use { rs -> return if (rs.next()) { val v = rs.getLong(1); if (rs.wasNull()) null else v } else null }
        }
    }

    suspend fun countHr(): Int = countQuery("SELECT COUNT(*) FROM `hrSample`")
    suspend fun maxHrTs(): Long {
        conn().createStatement().use { stmt ->
            stmt.executeQuery("SELECT COALESCE(MAX(ts), 0) FROM `hrSample`").use { rs ->
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }
    suspend fun countRr(): Int = countQuery("SELECT COUNT(*) FROM `rrInterval`")
    suspend fun countEvents(): Int = countQuery("SELECT COUNT(*) FROM `event`")
    suspend fun countBattery(): Int = countQuery("SELECT COUNT(*) FROM `battery`")
    suspend fun countSpo2(): Int = countQuery("SELECT COUNT(*) FROM `spo2Sample`")
    suspend fun countSkinTemp(): Int = countQuery("SELECT COUNT(*) FROM `skinTempSample`")
    suspend fun countSteps(): Int = countQuery("SELECT COUNT(*) FROM `stepSample`")
    suspend fun countResp(): Int = countQuery("SELECT COUNT(*) FROM `respSample`")
    suspend fun countGravity(): Int = countQuery("SELECT COUNT(*) FROM `gravitySample`")

    private fun countQuery(sql: String): Int {
        conn().createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Live convenience reads
    // ------------------------------------------------------------------

    suspend fun latestHr(deviceId: String): HrSample? {
        conn().prepareStatement("SELECT * FROM `hrSample` WHERE deviceId = ? ORDER BY ts DESC LIMIT 1").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) HrSample(rs.getString("deviceId"), rs.getLong("ts"), rs.getInt("bpm"), rs.getInt("synced")) else null
            }
        }
    }

    suspend fun latestBattery(deviceId: String): BatterySample? {
        conn().prepareStatement("SELECT * FROM `battery` WHERE deviceId = ? ORDER BY ts DESC LIMIT 1").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs -> return if (rs.next()) readBattery(rs) else null }
        }
    }

    // ------------------------------------------------------------------
    // MARK: - #547 one-time heal: prune rows with implausible timestamps
    // ------------------------------------------------------------------

    suspend fun pruneHrByTs(minTs: Long, maxTs: Long): Int = pruneByTs("hrSample", minTs, maxTs)
    suspend fun prunePpgHrByTs(minTs: Long, maxTs: Long): Int = pruneByTs("ppgHrSample", minTs, maxTs)
    suspend fun pruneRrByTs(minTs: Long, maxTs: Long): Int = pruneByTs("rrInterval", minTs, maxTs)
    suspend fun pruneSkinTempByTs(minTs: Long, maxTs: Long): Int = pruneByTs("skinTempSample", minTs, maxTs)
    suspend fun pruneStepByTs(minTs: Long, maxTs: Long): Int = pruneByTs("stepSample", minTs, maxTs)
    suspend fun pruneRespByTs(minTs: Long, maxTs: Long): Int = pruneByTs("respSample", minTs, maxTs)
    suspend fun pruneGravityByTs(minTs: Long, maxTs: Long): Int = pruneByTs("gravitySample", minTs, maxTs)
    suspend fun pruneSpo2ByTs(minTs: Long, maxTs: Long): Int = pruneByTs("spo2Sample", minTs, maxTs)
    suspend fun pruneEventByTs(minTs: Long, maxTs: Long): Int = pruneByTs("event", minTs, maxTs)
    suspend fun pruneBatteryByTs(minTs: Long, maxTs: Long): Int = pruneByTs("battery", minTs, maxTs)

    private fun pruneByTs(table: String, minTs: Long, maxTs: Long): Int {
        conn().prepareStatement("DELETE FROM `$table` WHERE ts < ? OR ts > ?").use { ps ->
            ps.setLong(1, minTs); ps.setLong(2, maxTs)
            return ps.executeUpdate()
        }
    }

    suspend fun pruneDailyMetricByDay(today: String, minDay: String): Int {
        conn().prepareStatement("DELETE FROM `dailyMetric` WHERE day > ? OR (day < ? AND deviceId LIKE '%-noop')").use { ps ->
            ps.setString(1, today); ps.setString(2, minDay)
            return ps.executeUpdate()
        }
    }

    suspend fun pruneSleepSessionByTs(minTs: Long, maxTs: Long): Int {
        conn().prepareStatement("DELETE FROM `sleepSession` WHERE startTs > ? OR (startTs < ? AND deviceId LIKE '%-noop')").use { ps ->
            ps.setLong(1, maxTs); ps.setLong(2, minTs)
            return ps.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // MARK: - Helpers
    // ------------------------------------------------------------------

    companion object {
        /** The constant device-id the daily marker projection is written under. */
        const val LAB_BOOK_SOURCE_ID = "lab-book"
    }

    /**
     * Batch INSERT OR IGNORE: returns a list mirroring the input rows where 1 = inserted,
     * -1 = skipped (already present). Mirrors Room's @Insert(IGNORE) return shape.
     */
    private fun <T> batchInsertIgnore(rows: List<T>, sql: String, binder: (PreparedStatement, T) -> Unit): List<Long> {
        if (rows.isEmpty()) return emptyList()
        conn().prepareStatement(sql).use { ps ->
            for (r in rows) { binder(ps, r); ps.addBatch() }
            val counts = ps.executeBatch()
            return counts.map { if (it > 0) 1L else -1L }
        }
    }

    private fun setNullableDouble(ps: PreparedStatement, idx: Int, v: Double?) {
        if (v != null) ps.setDouble(idx, v) else ps.setNull(idx, java.sql.Types.REAL)
    }

    private fun setNullableInt(ps: PreparedStatement, idx: Int, v: Int?) {
        if (v != null) ps.setInt(idx, v) else ps.setNull(idx, java.sql.Types.INTEGER)
    }

    private fun setNullableLong(ps: PreparedStatement, idx: Int, v: Long?) {
        if (v != null) ps.setLong(idx, v) else ps.setNull(idx, java.sql.Types.INTEGER)
    }

    private fun getNullableDouble(rs: ResultSet, col: String): Double? {
        val v = rs.getDouble(col)
        return if (rs.wasNull()) null else v
    }

    private fun getNullableInt(rs: ResultSet, col: String): Int? {
        val v = rs.getInt(col)
        return if (rs.wasNull()) null else v
    }

    private fun getNullableLong(rs: ResultSet, col: String): Long? {
        val v = rs.getLong(col)
        return if (rs.wasNull()) null else v
    }

    private fun getNullableString(rs: ResultSet, col: String): String? {
        val v = rs.getString(col)
        return if (rs.wasNull()) null else v
    }

    private fun readPairedDevice(rs: ResultSet): PairedDeviceRow = PairedDeviceRow(
        id = rs.getString("id"),
        brand = rs.getString("brand"),
        model = rs.getString("model"),
        nickname = getNullableString(rs, "nickname"),
        peripheralId = getNullableString(rs, "peripheralId"),
        sourceKind = rs.getString("sourceKind"),
        capabilities = rs.getString("capabilities"),
        status = rs.getString("status"),
        addedAt = rs.getLong("addedAt"),
        lastSeenAt = rs.getLong("lastSeenAt"),
    )

    private fun readBattery(rs: ResultSet): BatterySample {
        val charging = rs.getInt("charging")
        return BatterySample(
            deviceId = rs.getString("deviceId"),
            ts = rs.getLong("ts"),
            soc = getNullableDouble(rs, "soc"),
            mv = getNullableInt(rs, "mv"),
            charging = if (rs.wasNull()) null else charging != 0,
            synced = rs.getInt("synced"),
        )
    }

    private fun readDailyMetric(rs: ResultSet): DailyMetric = DailyMetric(
        deviceId = rs.getString("deviceId"),
        day = rs.getString("day"),
        totalSleepMin = getNullableDouble(rs, "totalSleepMin"),
        efficiency = getNullableDouble(rs, "efficiency"),
        deepMin = getNullableDouble(rs, "deepMin"),
        remMin = getNullableDouble(rs, "remMin"),
        lightMin = getNullableDouble(rs, "lightMin"),
        disturbances = getNullableInt(rs, "disturbances"),
        restingHr = getNullableInt(rs, "restingHr"),
        avgHrv = getNullableDouble(rs, "avgHrv"),
        recovery = getNullableDouble(rs, "recovery"),
        strain = getNullableDouble(rs, "strain"),
        exerciseCount = getNullableInt(rs, "exerciseCount"),
        spo2Pct = getNullableDouble(rs, "spo2Pct"),
        skinTempDevC = getNullableDouble(rs, "skinTempDevC"),
        respRateBpm = getNullableDouble(rs, "respRateBpm"),
        steps = getNullableInt(rs, "steps"),
        activeKcalEst = getNullableDouble(rs, "activeKcalEst"),
    )

    private fun readSleepSession(rs: ResultSet): SleepSession = SleepSession(
        deviceId = rs.getString("deviceId"),
        startTs = rs.getLong("startTs"),
        endTs = rs.getLong("endTs"),
        efficiency = getNullableDouble(rs, "efficiency"),
        restingHr = getNullableInt(rs, "restingHr"),
        avgHrv = getNullableDouble(rs, "avgHrv"),
        stagesJSON = getNullableString(rs, "stagesJSON"),
        userEdited = rs.getInt("userEdited") != 0,
        startTsAdjusted = getNullableLong(rs, "startTsAdjusted"),
        motionJSON = getNullableString(rs, "motionJSON"),
        sleepStateJSON = getNullableString(rs, "sleepStateJSON"),
    )

    private fun readWorkout(rs: ResultSet): WorkoutRow = WorkoutRow(
        deviceId = rs.getString("deviceId"),
        startTs = rs.getLong("startTs"),
        endTs = rs.getLong("endTs"),
        sport = rs.getString("sport"),
        source = rs.getString("source"),
        durationS = getNullableDouble(rs, "durationS"),
        energyKcal = getNullableDouble(rs, "energyKcal"),
        avgHr = getNullableInt(rs, "avgHr"),
        maxHr = getNullableInt(rs, "maxHr"),
        strain = getNullableDouble(rs, "strain"),
        distanceM = getNullableDouble(rs, "distanceM"),
        zonesJSON = getNullableString(rs, "zonesJSON"),
        notes = getNullableString(rs, "notes"),
        routePolyline = getNullableString(rs, "routePolyline"),
    )

    private fun readAppleDaily(rs: ResultSet): AppleDaily = AppleDaily(
        deviceId = rs.getString("deviceId"),
        day = rs.getString("day"),
        steps = getNullableInt(rs, "steps"),
        activeKcal = getNullableDouble(rs, "activeKcal"),
        basalKcal = getNullableDouble(rs, "basalKcal"),
        vo2max = getNullableDouble(rs, "vo2max"),
        avgHr = getNullableInt(rs, "avgHr"),
        maxHr = getNullableInt(rs, "maxHr"),
        walkingHr = getNullableInt(rs, "walkingHr"),
        weightKg = getNullableDouble(rs, "weightKg"),
    )

    private fun readLabMarker(rs: ResultSet): LabMarkerRow = LabMarkerRow(
        id = rs.getString("id"),
        deviceId = rs.getString("deviceId"),
        markerKey = rs.getString("markerKey"),
        category = rs.getString("category"),
        day = rs.getString("day"),
        takenAt = rs.getLong("takenAt"),
        value = getNullableDouble(rs, "value"),
        valueText = getNullableString(rs, "valueText"),
        unit = rs.getString("unit"),
        source = rs.getString("source"),
        note = getNullableString(rs, "note"),
        referenceText = getNullableString(rs, "referenceText"),
    )

    private fun readLiveSession(rs: ResultSet): LiveSessionRow = LiveSessionRow(
        deviceId = rs.getString("deviceId"),
        startTs = rs.getLong("startTs"),
        endTs = getNullableLong(rs, "endTs"),
        chargeAtStart = getNullableDouble(rs, "chargeAtStart"),
        floorBpm = rs.getDouble("floorBpm"),
        ceilingBpm = rs.getDouble("ceilingBpm"),
        inBandSec = rs.getDouble("inBandSec"),
        belowSec = rs.getDouble("belowSec"),
        aboveSec = rs.getDouble("aboveSec"),
        pushCount = rs.getInt("pushCount"),
        easeCount = rs.getInt("easeCount"),
        hrSource = rs.getString("hrSource"),
    )
}
