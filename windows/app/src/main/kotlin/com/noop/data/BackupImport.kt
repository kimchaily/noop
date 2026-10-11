package com.noop.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.sql.DriverManager
import java.sql.SQLException
import java.util.zip.ZipInputStream

/**
 * Import a `.noopbak` backup file (ZIP containing `noop-backup.sqlite`) produced by the
 * iOS / macOS NOOP app into the desktop app's local database.
 *
 * The backup is a standard ZIP archive (the same format the iOS/macOS app exports via
 * `DataBackup.runExport`): the SQLite database lives inside as `noop-backup.sqlite`,
 * optionally alongside a `settings.json` carrying whitelisted profile/display settings.
 * The SQLite file uses GRDB's migration system (`grdb_migrations` table) and has
 * `PRAGMA user_version = 0`, while the desktop app uses `PRAGMA user_version` for its
 * own migration tracking — so after swapping the file we must reconcile the schema.
 *
 * The import flow mirrors the iOS `DataBackup.restore` hardened path:
 *   1. Detect the ZIP by magic bytes (`PK\x03\x04`).
 *   2. Extract the SQLite entry to a temp directory.
 *   3. Validate the SQLite magic header (`SQLite format 3\0`).
 *   4. Close the live database connection (so file handles are released).
 *   5. Snapshot the current database to a timestamped sidecar (rollback safety).
 *   6. Remove the live DB + WAL/SHM sidecars, then copy the backup SQLite into place.
 *   7. Reconcile the schema: add any columns the desktop v16 schema expects that the
 *      GRDB backup may lack (e.g. `workout.routePolyline`), and set
 *      `PRAGMA user_version = 16` so the desktop migrator treats the file as current.
 *   8. Reopen the database — the next `DesktopDatabase.get` call rebuilds against the
 *      swapped-in file.
 *
 * The data tables (`hrSample`, `dailyMetric`, `sleepSession`, `workout`, etc.) share
 * the same column layout across iOS/GRDB and desktop/JDBC (both are ports of the same
 * Android Room schema), so the imported rows are directly readable by the desktop
 * repository without any row-level transformation.
 */
object BackupImport {

    /** The result of an import attempt. */
    sealed class Result {
        /** Import succeeded; the previous database was preserved at [sidecarPath]. */
        data class Success(val sidecarPath: String, val rowsImported: Map<String, Int>) : Result()
        /** Import failed; the live database was left untouched (or rolled back). */
        data class Failure(val message: String) : Result()
        /** The user dismissed the file picker. */
        object Cancelled : Result()
    }

    /**
     * Import a `.noopbak` (or legacy `.sqlite`) backup file into the desktop database at
     * [dbPath].
     *
     * The caller MUST ensure [DesktopDatabase.close] is called before invoking this method
     * (the file swap requires all file handles to be released). After a successful import,
     * the caller should call [DesktopDatabase.get] (or [NoopApplication.init]) to reopen
     * against the new file.
     *
     * @param backupFile the `.noopbak` ZIP or legacy `.sqlite` file to import.
     * @param dbPath the absolute path to the desktop app's SQLite database file.
     * @return the [Result] of the import.
     */
    fun import(backupFile: File, dbPath: String): Result {
        if (!backupFile.exists()) {
            return Result.Failure("Backup file not found: ${backupFile.absolutePath}")
        }

        val dbDir = File(dbPath).parentFile
        if (dbDir != null && !dbDir.exists()) dbDir.mkdirs()

        // 1. Extract the SQLite from the ZIP (or use the file directly if legacy SQLite).
        var extractedDir: File? = null
        val sqliteFile: File

        try {
            if (isZipFile(backupFile)) {
                extractedDir = createTempDir("noop-import")
                sqliteFile = extractZip(backupFile, extractedDir)
                    ?: return Result.Failure("The backup archive doesn't contain a database file.")
            } else {
                extractedDir = null
                sqliteFile = backupFile
            }
        } catch (e: Exception) {
            extractedDir?.deleteRecursively()
            return Result.Failure("Couldn't open the backup archive: ${e.message}")
        }

        try {
            // 2. Validate: must be a real SQLite database.
            if (!isSQLiteFile(sqliteFile)) {
                return Result.Failure("That file isn't a NOOP backup — it doesn't look like a SQLite database.")
            }

            // 3. Count rows in the backup for the success report.
            val rowCounts = countRows(sqliteFile)

            // 4. Snapshot the current DB to a timestamped sidecar for rollback safety.
            val dbFile = File(dbPath)
            val sidecar = File(dbDir, "whoop-replaced-${timestamp()}.sqlite")
            if (dbFile.exists()) {
                if (sidecar.exists()) sidecar.delete()
                dbFile.copyTo(sidecar, overwrite = true)
            }

            // 5. Remove the live DB + WAL/SHM sidecars, then copy the backup in.
            dbFile.delete()
            File("$dbPath-wal").delete()
            File("$dbPath-shm").delete()

            try {
                sqliteFile.copyTo(dbFile, overwrite = true)
            } catch (e: Exception) {
                // Roll back to the snapshot so a failed copy doesn't leave an empty DB.
                if (sidecar.exists()) {
                    sidecar.copyTo(dbFile, overwrite = true)
                }
                return Result.Failure("Import failed. Your existing data was kept. ${e.message}")
            }

            // 6. Reconcile the schema: add missing columns and set user_version = 16.
            reconcileSchema(dbFile)

            return Result.Success(
                sidecarPath = if (sidecar.exists()) sidecar.absolutePath else dbPath,
                rowsImported = rowCounts,
            )
        } finally {
            extractedDir?.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // ZIP extraction
    // -----------------------------------------------------------------------

    /** Read the first 4 bytes and check for the ZIP PK magic (`PK\x03\x04`). */
    private fun isZipFile(file: File): Boolean {
        FileInputStream(file).use { fis ->
            val head = ByteArray(4)
            val read = fis.read(head)
            return read >= 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    }

    /**
     * Extract the SQLite entry from a `.noopbak` ZIP into [destDir]. Returns the extracted
     * `.sqlite` file, or null if the archive contains no such entry.
     */
    private fun extractZip(zipFile: File, destDir: File): File? {
        var sqliteOut: File? = null
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = File(entry.name).name // flatten — use last path component only
                val outFile = File(destDir, name)
                if (!entry.isDirectory) {
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                    if (name.endsWith(".sqlite")) {
                        sqliteOut = outFile
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return sqliteOut
    }

    // -----------------------------------------------------------------------
    // SQLite validation + schema reconciliation
    // -----------------------------------------------------------------------

    /** Read the first 16 bytes and check for the SQLite magic header. */
    private fun isSQLiteFile(file: File): Boolean {
        FileInputStream(file).use { fis ->
            val head = ByteArray(16)
            val read = fis.read(head)
            if (read < 16) return false
            val magic = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0x00.toByte()
            return head.contentEquals(magic)
        }
    }

    /**
     * Count rows in every data table the desktop app cares about, for the success report.
     * Opens the backup SQLite read-only so the file is never mutated.
     */
    private fun countRows(sqliteFile: File): Map<String, Int> {
        val tables = listOf(
            "hrSample", "rrInterval", "event", "battery", "spo2Sample",
            "skinTempSample", "respSample", "gravitySample", "stepSample",
            "dailyMetric", "sleepSession", "workout", "journal",
            "metricSeries", "labMarker", "liveSession", "ppgHrSample",
            "sleepStateSample", "pairedDevice", "device",
        )
        val counts = LinkedHashMap<String, Int>()
        try {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:${sqliteFile.absolutePath}")
            conn.use { c ->
                c.createStatement().use { stmt ->
                    for (t in tables) {
                        try {
                            stmt.executeQuery("SELECT COUNT(*) FROM `$t`").use { rs ->
                                counts[t] = rs.getInt(1)
                            }
                        } catch (_: SQLException) {
                            // Table doesn't exist in this backup — skip.
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Best-effort count; a failure here doesn't block the import.
        }
        return counts
    }

    /**
     * Reconcile the imported SQLite's schema with the desktop app's expected v16 shape.
     *
     * The iOS/GRDB backup may lack columns that the desktop Room-v16 schema includes
     * (e.g. `workout.routePolyline` was added in migration v3→v4 on Android but may be
     * absent from a GRDB backup that never had that column). We add any missing columns
     * with `ALTER TABLE … ADD COLUMN` (nullable, no default — matching the Room shape).
     *
     * We also set `PRAGMA user_version = 16` so the desktop migrator recognises the file
     * as current and doesn't attempt to re-run migrations (which would fail on the
     * already-existing tables / columns).
     */
    private fun reconcileSchema(dbFile: File) {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.use { c ->
            c.createStatement().use { stmt ->
                // Set the user_version so the desktop migrator treats the file as v16.
                stmt.execute("PRAGMA user_version = ${DesktopDatabase.SCHEMA_VERSION}")

                // Ensure every column the desktop v16 schema expects is present.
                // Each call is guarded by hasColumn() so it's a no-op if the column exists.
                ensureColumn(stmt, "workout", "routePolyline", "TEXT")
                ensureColumn(stmt, "battery", "charging", "INTEGER")
                ensureColumn(stmt, "dailyMetric", "spo2Pct", "REAL")
                ensureColumn(stmt, "dailyMetric", "skinTempDevC", "REAL")
                ensureColumn(stmt, "dailyMetric", "respRateBpm", "REAL")
                ensureColumn(stmt, "dailyMetric", "steps", "INTEGER")
                ensureColumn(stmt, "dailyMetric", "activeKcalEst", "REAL")
                ensureColumn(stmt, "sleepSession", "userEdited", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(stmt, "sleepSession", "startTsAdjusted", "INTEGER")
                ensureColumn(stmt, "sleepSession", "motionJSON", "TEXT")
                ensureColumn(stmt, "sleepSession", "sleepStateJSON", "TEXT")
                ensureColumn(stmt, "stepSample", "activityClass", "INTEGER")
                ensureColumn(stmt, "journal", "numericValue", "REAL")
                ensureColumn(stmt, "pairedDevice", "peripheralId", "TEXT")

                // Ensure the pairedDevice "my-whoop" seed row exists (INSERT OR IGNORE).
                val now = System.currentTimeMillis() / 1000
                stmt.execute(
                    "INSERT OR IGNORE INTO `pairedDevice` " +
                        "(`id`, `brand`, `model`, `nickname`, `sourceKind`, `capabilities`, " +
                        "`status`, `addedAt`, `lastSeenAt`) VALUES " +
                        "('my-whoop', 'WHOOP', 'WHOOP', NULL, 'liveBLE', " +
                        "'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active', $now, $now)",
                )
            }
        }
    }

    /**
     * Add [column] of [type] to [table] if it doesn't already exist. The ALTER TABLE
     * is wrapped in a try/catch because SQLite throws if the column is already present
     * (there's no IF NOT EXISTS for ADD COLUMN).
     */
    private fun ensureColumn(stmt: java.sql.Statement, table: String, column: String, type: String) {
        if (hasColumn(stmt, table, column)) return
        try {
            stmt.execute("ALTER TABLE `$table` ADD COLUMN `$column` $type")
        } catch (_: SQLException) {
            // Column may have been added concurrently, or the table doesn't exist —
            // either way, not fatal: the desktop migrator will handle it on open.
        }
    }

    /** True if [column] exists in [table] (read-only PRAGMA table_info probe). */
    private fun hasColumn(stmt: java.sql.Statement, table: String, column: String): Boolean {
        try {
            stmt.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == column) return true
                }
            }
        } catch (_: SQLException) {
            // Table doesn't exist — treat as "column absent".
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun timestamp(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US)
        return f.format(java.util.Date())
    }

    private fun createTempDir(prefix: String): File {
        val tmp = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        tmp.mkdirs()
        return tmp
    }
}
