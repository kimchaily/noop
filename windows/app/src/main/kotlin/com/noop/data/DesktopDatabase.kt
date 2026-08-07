package com.noop.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement

/**
 * Local SQLite database for the NOOP Windows desktop port — the pure-JDBC replacement for
 * Android's Room [WhoopDatabase]. Holds phone-collected raw streams AND the offline cache of
 * server-computed derived metrics.
 *
 * The schema is byte-for-byte identical to the Android Room schema (version 16): every table
 * name, column name, column type, NOT NULL constraint, PRIMARY KEY, and index matches what
 * Room generates from the entity annotations + migration SQL in WhoopDatabase.kt. The CREATE
 * TABLE statements below are constructed from the entity definitions and the migration SQL
 * strings, representing the FINAL v16 shape (what Room creates on a fresh install).
 *
 * The migrator runs the additive migrations (v2→v3 … v15→v16) in sequence for an existing
 * database file that was created at an earlier version, exactly mirroring Room's migration
 * path. A fresh database is created straight at v16 (no migrations run), matching Room's
 * fresh-install callback behaviour (including the "my-whoop" pairedDevice seed).
 *
 * Connection management: a single process-wide [Connection] is held, configured with WAL
 * journal mode and a busy timeout. All access is synchronised on the [lock] monitor so the
 * connection is never used concurrently from two threads (SQLite serialises writes anyway,
 * but JDBC Connection objects are not thread-safe).
 */
class DesktopDatabase private constructor(private val dbPath: String) {

    private val lock = Any()
    private var connection: Connection? = null

    /**
     * Initialise the database: load the SQLite JDBC driver, open the connection, set WAL +
     * busy_timeout, then either create the full fresh schema (version 16) or run the additive
     * migrations up to 16. Seeds the canonical "my-whoop" pairedDevice row on first create.
     *
     * Safe to call once at startup; subsequent calls are no-ops.
     */
    fun init() {
        synchronized(lock) {
            if (connection != null && !connection!!.isClosed) return
            // Explicitly load the JDBC driver (required on some classloader setups).
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            conn.autoCommit = true
            // PRAGMA tweaks must run outside a transaction.
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode = WAL")
                    stmt.execute("PRAGMA busy_timeout = 5000")
                    stmt.execute("PRAGMA foreign_keys = ON")
                    stmt.execute("PRAGMA synchronous = NORMAL")
                }
            } catch (e: SQLException) {
                conn.close()
                throw e
            }
            connection = conn
            createOrMigrate(conn)
        }
    }

    /**
     * Close the database connection, releasing all file handles. The next [init] call
     * rebuilds against whatever file is on disk, used by backup-restore to swap the file.
     */
    fun close() {
        synchronized(lock) {
            connection?.let { conn ->
                try {
                    if (!conn.isClosed) {
                        conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                    }
                } catch (_: SQLException) {
                    // best-effort checkpoint
                }
                try { conn.close() } catch (_: SQLException) {}
            }
            connection = null
        }
    }

    /**
     * Return the shared JDBC connection. Callers must NOT close it. All DML should be
     * performed through [DesktopDao] which manages its own PreparedStatements.
     */
    fun getConnection(): Connection {
        return connection ?: throw IllegalStateException("DesktopDatabase not initialised — call init() first")
    }

    /**
     * Execute [block] inside a single SQL transaction (auto-commit off, commit on success,
     * rollback on exception). Mirrors Room's `withTransaction`.
     */
    fun <R> withTransaction(block: () -> R): R {
        synchronized(lock) {
            val conn = getConnection()
            val prev = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block()
                conn.commit()
                return result
            } catch (e: Throwable) {
                try { conn.rollback() } catch (_: SQLException) {}
                throw e
            } finally {
                try { conn.autoCommit = prev } catch (_: SQLException) {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // Schema creation / migration
    // -----------------------------------------------------------------------

    /**
     * The current schema version. Matches the Room @Database(version = 16).
     */
    val schemaVersion: Int get() = SCHEMA_VERSION

    private fun createOrMigrate(conn: Connection) {
        val currentVersion = readUserVersion(conn)
        if (currentVersion >= SCHEMA_VERSION) return
        if (currentVersion == 0) {
            // Fresh database: create the full v16 schema in one transaction, matching Room's
            // fresh-install path (no migrations run).
            createFreshSchema(conn)
            seedPairedDevice(conn)
            writeUserVersion(conn, SCHEMA_VERSION)
        } else {
            // Existing database at an older version: run the additive migrations in sequence.
            runMigrations(conn, currentVersion)
            writeUserVersion(conn, SCHEMA_VERSION)
        }
    }

    private fun readUserVersion(conn: Connection): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA user_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    private fun writeUserVersion(conn: Connection, version: Int) {
        conn.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    /**
     * Create the full v16 schema (all tables + indexes at their final shape). Each CREATE TABLE
     * statement matches what Room generates from the entity annotations on a fresh install.
     */
    private fun createFreshSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            for (sql in FRESH_SCHEMA_SQL) {
                stmt.execute(sql)
            }
        }
    }

    /**
     * Seed the canonical "my-whoop" pairedDevice row on first create, matching Room's
     * onCreate callback. INSERT OR IGNORE so a re-run / backup-restore is a no-op.
     */
    private fun seedPairedDevice(conn: Connection) {
        val now = System.currentTimeMillis() / 1000
        conn.createStatement().use { stmt ->
            stmt.execute(
                "INSERT OR IGNORE INTO `pairedDevice` " +
                    "(`id`, `brand`, `model`, `nickname`, `sourceKind`, `capabilities`, " +
                    "`status`, `addedAt`, `lastSeenAt`) VALUES " +
                    "('my-whoop', 'WHOOP', 'WHOOP', NULL, 'liveBLE', " +
                    "'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active', $now, $now)",
            )
        }
    }

    /**
     * Run the additive migrations from [fromVersion] up to [SCHEMA_VERSION]. Each migration's
     * SQL is taken verbatim from the Room MIGRATION_n_n+1 objects in WhoopDatabase.kt.
     */
    private fun runMigrations(conn: Connection, fromVersion: Int) {
        for (migration in MIGRATIONS) {
            if (migration.from <= fromVersion) continue
            if (migration.from != fromVersion && migration.from < fromVersion) continue
            // Run migrations in strict ascending order starting from fromVersion.
        }
        // Collect and run every migration whose [from] >= fromVersion, in ascending order.
        val toRun = MIGRATIONS.filter { it.from >= fromVersion }.sortedBy { it.from }
        for (migration in toRun) {
            for (sql in migration.sql) {
                conn.createStatement().use { it.execute(sql) }
            }
            // The v7→v8 migration seeds "my-whoop"; run it for parity.
            if (migration.from == 7) {
                seedPairedDevice(conn)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Schema SQL constants
    // -----------------------------------------------------------------------

    companion object {
        /** Matches the Room @Database(version = 16). */
        const val SCHEMA_VERSION = 16

        /** Matches WhoopDatabase.DB_NAME. */
        const val DB_NAME = "noop_whoop.db"

        @Volatile
        private var instance: DesktopDatabase? = null

        /**
         * Process-wide singleton. Safe to call from any thread.
         * @param dbPath absolute path to the SQLite database file.
         */
        fun get(dbPath: String): DesktopDatabase =
            instance ?: synchronized(this) {
                instance ?: DesktopDatabase(dbPath).also {
                    it.init()
                    instance = it
                }
            }

        /**
         * Close and forget the singleton so all file handles are released.
         * The next [get] call rebuilds against whatever file is on disk.
         */
        fun close() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        // -------------------------------------------------------------------
        // Fresh schema — the FULL v16 shape Room creates on a fresh install.
        // Each CREATE TABLE matches Room's generated DDL from the entity defs.
        // Column types: String→TEXT, Long/Int/Boolean→INTEGER, Double→REAL.
        // Nullable Kotlin fields → no NOT NULL; non-null fields → NOT NULL.
        // -------------------------------------------------------------------

        internal val FRESH_SCHEMA_SQL: List<String> = listOf(
            // device (v1)
            "CREATE TABLE IF NOT EXISTS `device` (`id` TEXT NOT NULL, `mac` TEXT, `name` TEXT, " +
                "`firstSeen` INTEGER, `lastSeen` INTEGER, PRIMARY KEY(`id`))",

            // hrSample (v1)
            "CREATE TABLE IF NOT EXISTS `hrSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`bpm` INTEGER NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",

            // ppgHrSample (v6 migration shape)
            "CREATE TABLE IF NOT EXISTS `ppgHrSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`bpm` INTEGER NOT NULL, `conf` REAL NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // rrInterval (v1)
            "CREATE TABLE IF NOT EXISTS `rrInterval` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`rrMs` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`, `rrMs`))",

            // event (v1)
            "CREATE TABLE IF NOT EXISTS `event` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `payloadJSON` TEXT NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`, `kind`))",

            // battery (v1 + v6 charging column already in the base Android shape)
            "CREATE TABLE IF NOT EXISTS `battery` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`soc` REAL, `mv` INTEGER, `charging` INTEGER, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // spo2Sample (v3 Swift)
            "CREATE TABLE IF NOT EXISTS `spo2Sample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`red` INTEGER NOT NULL, `ir` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // skinTempSample (v3 Swift)
            "CREATE TABLE IF NOT EXISTS `skinTempSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`raw` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // stepSample (v3 migration + v13 activityClass)
            "CREATE TABLE IF NOT EXISTS `stepSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`counter` INTEGER NOT NULL, `synced` INTEGER NOT NULL, `activityClass` INTEGER, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // sleepStateSample (v15 migration)
            "CREATE TABLE IF NOT EXISTS `sleepStateSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`state` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",

            // respSample (v3 Swift)
            "CREATE TABLE IF NOT EXISTS `respSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`raw` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // gravitySample (v3 Swift)
            "CREATE TABLE IF NOT EXISTS `gravitySample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`x` REAL NOT NULL, `y` REAL NOT NULL, `z` REAL NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`))",

            // dailyMetric (v4 + v7 spo2Pct/skinTempDevC/respRateBpm + v3 steps/activeKcalEst)
            "CREATE TABLE IF NOT EXISTS `dailyMetric` (`deviceId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                "`totalSleepMin` REAL, `efficiency` REAL, `deepMin` REAL, `remMin` REAL, " +
                "`lightMin` REAL, `disturbances` INTEGER, `restingHr` INTEGER, `avgHrv` REAL, " +
                "`recovery` REAL, `strain` REAL, `exerciseCount` INTEGER, `spo2Pct` REAL, " +
                "`skinTempDevC` REAL, `respRateBpm` REAL, `steps` INTEGER, `activeKcalEst` REAL, " +
                "PRIMARY KEY(`deviceId`, `day`))",

            // sleepSession (v4 + v7 userEdited/startTsAdjusted + v12 motionJSON/sleepStateJSON)
            "CREATE TABLE IF NOT EXISTS `sleepSession` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, `efficiency` REAL, " +
                "`restingHr` INTEGER, `avgHrv` REAL, `stagesJSON` TEXT, " +
                "`userEdited` INTEGER NOT NULL DEFAULT 0, `startTsAdjusted` INTEGER, " +
                "`motionJSON` TEXT, `sleepStateJSON` TEXT, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",

            // metricSeries (v9 Swift) + its secondary index
            "CREATE TABLE IF NOT EXISTS `metricSeries` (`deviceId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                "`key` TEXT NOT NULL, `value` REAL NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `day`, `key`))",
            "CREATE INDEX IF NOT EXISTS `idx_metricSeries_device_key_day` " +
                "ON `metricSeries` (`deviceId`, `key`, `day`)",

            // labMarker (v11 migration) + its three indexes
            "CREATE TABLE IF NOT EXISTS `labMarker` (`id` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`markerKey` TEXT NOT NULL, `category` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                "`takenAt` INTEGER NOT NULL, `value` REAL, `valueText` TEXT, " +
                "`unit` TEXT NOT NULL, `source` TEXT NOT NULL, `note` TEXT, `referenceText` TEXT, " +
                "PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_labMarker_natural` " +
                "ON `labMarker` (`deviceId`, `markerKey`, `takenAt`, `source`)",
            "CREATE INDEX IF NOT EXISTS `idx_labMarker_device_marker_takenAt` " +
                "ON `labMarker` (`deviceId`, `markerKey`, `takenAt`)",
            "CREATE INDEX IF NOT EXISTS `idx_labMarker_device_category` " +
                "ON `labMarker` (`deviceId`, `category`)",

            // journal (v8 + v14 numericValue)
            "CREATE TABLE IF NOT EXISTS `journal` (`deviceId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                "`question` TEXT NOT NULL, `answeredYes` INTEGER NOT NULL, `notes` TEXT, " +
                "`numericValue` REAL, PRIMARY KEY(`deviceId`, `day`, `question`))",

            // workout (v8 + v4 routePolyline)
            "CREATE TABLE IF NOT EXISTS `workout` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, `sport` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, `durationS` REAL, `energyKcal` REAL, `avgHr` INTEGER, " +
                "`maxHr` INTEGER, `strain` REAL, `distanceM` REAL, `zonesJSON` TEXT, `notes` TEXT, " +
                "`routePolyline` TEXT, PRIMARY KEY(`deviceId`, `startTs`, `sport`))",

            // dismissedWorkout (v5 migration)
            "CREATE TABLE IF NOT EXISTS `dismissedWorkout` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",

            // dismissedSleep (v10 migration)
            "CREATE TABLE IF NOT EXISTS `dismissedSleep` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",

            // appleDaily (v8 Swift)
            "CREATE TABLE IF NOT EXISTS `appleDaily` (`deviceId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                "`steps` INTEGER, `activeKcal` REAL, `basalKcal` REAL, `vo2max` REAL, " +
                "`avgHr` INTEGER, `maxHr` INTEGER, `walkingHr` INTEGER, `weightKg` REAL, " +
                "PRIMARY KEY(`deviceId`, `day`))",

            // pairedDevice (v8 + v9 peripheralId)
            "CREATE TABLE IF NOT EXISTS `pairedDevice` (`id` TEXT NOT NULL, `brand` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, `nickname` TEXT, `peripheralId` TEXT, " +
                "`sourceKind` TEXT NOT NULL, `capabilities` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",

            // dayOwnership (v8 migration)
            "CREATE TABLE IF NOT EXISTS `dayOwnership` (`day` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `locked` INTEGER NOT NULL, PRIMARY KEY(`day`))",

            // liveSession (v16 migration)
            "CREATE TABLE IF NOT EXISTS `liveSession` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER, `chargeAtStart` REAL, " +
                "`floorBpm` REAL NOT NULL, `ceilingBpm` REAL NOT NULL, `inBandSec` REAL NOT NULL, " +
                "`belowSec` REAL NOT NULL, `aboveSec` REAL NOT NULL, `pushCount` INTEGER NOT NULL, " +
                "`easeCount` INTEGER NOT NULL, `hrSource` TEXT NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",
        )

        // -------------------------------------------------------------------
        // Migrations — verbatim SQL from WhoopDatabase.kt's MIGRATION_n_n+1 objects.
        // Each Migration holds the [from] version and the list of SQL statements.
        // -------------------------------------------------------------------

        internal data class Migration(val from: Int, val to: Int, val sql: List<String>)

        /** v2→v3: stepSample table + dailyMetric.steps/activeKcalEst. */
        internal val MIGRATION_2_3 = Migration(2, 3, listOf(
            "CREATE TABLE IF NOT EXISTS `stepSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `counter` INTEGER NOT NULL, " +
                "`synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
            "ALTER TABLE `dailyMetric` ADD COLUMN `steps` INTEGER",
            "ALTER TABLE `dailyMetric` ADD COLUMN `activeKcalEst` REAL",
        ))

        /** v3→v4: workout.routePolyline. */
        internal val MIGRATION_3_4 = Migration(3, 4, listOf(
            "ALTER TABLE `workout` ADD COLUMN `routePolyline` TEXT",
        ))

        /** v4→v5: dismissedWorkout table. */
        internal val MIGRATION_4_5 = Migration(4, 5, listOf(
            "CREATE TABLE IF NOT EXISTS `dismissedWorkout` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",
        ))

        /** v5→v6: ppgHrSample table. */
        internal val MIGRATION_5_6 = Migration(5, 6, listOf(
            "CREATE TABLE IF NOT EXISTS `ppgHrSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, `conf` REAL NOT NULL, " +
                "`synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        ))

        /** v6→v7: sleepSession.userEdited + startTsAdjusted. */
        internal val MIGRATION_6_7 = Migration(6, 7, listOf(
            "ALTER TABLE `sleepSession` ADD COLUMN `userEdited` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `sleepSession` ADD COLUMN `startTsAdjusted` INTEGER",
        ))

        /** v7→v8: pairedDevice + dayOwnership tables + "my-whoop" seed. */
        internal val MIGRATION_7_8 = Migration(7, 8, listOf(
            "CREATE TABLE IF NOT EXISTS `pairedDevice` (`id` TEXT NOT NULL, " +
                "`brand` TEXT NOT NULL, `model` TEXT NOT NULL, `nickname` TEXT, " +
                "`sourceKind` TEXT NOT NULL, `capabilities` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `dayOwnership` (`day` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `locked` INTEGER NOT NULL, PRIMARY KEY(`day`))",
            // The seed is applied separately in runMigrations() for v7→v8 parity.
        ))

        /** v8→v9: pairedDevice.peripheralId. */
        internal val MIGRATION_8_9 = Migration(8, 9, listOf(
            "ALTER TABLE `pairedDevice` ADD COLUMN `peripheralId` TEXT",
        ))

        /** v9→v10: dismissedSleep table. */
        internal val MIGRATION_9_10 = Migration(9, 10, listOf(
            "CREATE TABLE IF NOT EXISTS `dismissedSleep` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",
        ))

        /** v10→v11: labMarker table + indexes. */
        internal val MIGRATION_10_11 = Migration(10, 11, listOf(
            "CREATE TABLE IF NOT EXISTS `labMarker` (`id` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `markerKey` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `day` TEXT NOT NULL, `takenAt` INTEGER NOT NULL, " +
                "`value` REAL, `valueText` TEXT, `unit` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`note` TEXT, `referenceText` TEXT, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_labMarker_natural` " +
                "ON `labMarker` (`deviceId`, `markerKey`, `takenAt`, `source`)",
            "CREATE INDEX IF NOT EXISTS `idx_labMarker_device_marker_takenAt` " +
                "ON `labMarker` (`deviceId`, `markerKey`, `takenAt`)",
            "CREATE INDEX IF NOT EXISTS `idx_labMarker_device_category` " +
                "ON `labMarker` (`deviceId`, `category`)",
        ))

        /** v11→v12: sleepSession.motionJSON + sleepStateJSON. */
        internal val MIGRATION_11_12 = Migration(11, 12, listOf(
            "ALTER TABLE `sleepSession` ADD COLUMN `motionJSON` TEXT",
            "ALTER TABLE `sleepSession` ADD COLUMN `sleepStateJSON` TEXT",
        ))

        /** v12→v13: stepSample.activityClass. */
        internal val MIGRATION_12_13 = Migration(12, 13, listOf(
            "ALTER TABLE `stepSample` ADD COLUMN `activityClass` INTEGER",
        ))

        /** v13→v14: journal.numericValue. */
        internal val MIGRATION_13_14 = Migration(13, 14, listOf(
            "ALTER TABLE `journal` ADD COLUMN `numericValue` REAL",
        ))

        /** v14→v15: sleepStateSample table. */
        internal val MIGRATION_14_15 = Migration(14, 15, listOf(
            "CREATE TABLE IF NOT EXISTS `sleepStateSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `state` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        ))

        /** v15→v16: liveSession table. */
        internal val MIGRATION_15_16 = Migration(15, 16, listOf(
            "CREATE TABLE IF NOT EXISTS `liveSession` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER, `chargeAtStart` REAL, " +
                "`floorBpm` REAL NOT NULL, `ceilingBpm` REAL NOT NULL, `inBandSec` REAL NOT NULL, " +
                "`belowSec` REAL NOT NULL, `aboveSec` REAL NOT NULL, `pushCount` INTEGER NOT NULL, " +
                "`easeCount` INTEGER NOT NULL, `hrSource` TEXT NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",
        ))

        /** All migrations in ascending order. */
        internal val MIGRATIONS: List<Migration> = listOf(
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            MIGRATION_14_15, MIGRATION_15_16,
        )
    }
}
