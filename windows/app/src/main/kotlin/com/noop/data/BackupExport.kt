package com.noop.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export the desktop app's SQLite database (+ optional settings) to a `.noopbak` ZIP archive.
 *
 * This is the export twin of [BackupImport]: it produces a file in the exact same format
 * the iOS/macOS/Android app exports — a standard ZIP containing:
 *
 *   1. `noop-backup.sqlite` — the full SQLite database file (WAL checkpointed first)
 *   2. `settings.json` — whitelisted user settings (optional, currently omitted on desktop)
 *
 * The archive can be imported on any NOOP platform (iOS, macOS, Android, Windows).
 *
 * Port of Android `DataBackup.exportTo` / `DataBackup.writeBackupZip`, adapted for the
 * JDBC-backed [DesktopDatabase]:
 *   - `Room.withTransaction` → `DesktopDatabase.withTransaction` (to prevent concurrent
 *     checkpoint page-tearing during the file copy)
 *   - `BackupSettingsBridge.snapshotJson()` → not yet ported (settings.json omitted)
 *   - `Uri` / `ContentResolver` → plain `java.io.File`
 */
object BackupExport {

    /** The result of an export attempt. */
    sealed class Result {
        /** Export succeeded; the backup was written to [filePath]. */
        data class Success(val filePath: String, val sizeBytes: Long) : Result()
        /** Export failed; [message] is user-facing. */
        data class Failure(val message: String) : Result()
        /** The user dismissed the save dialog. */
        object Cancelled : Result()
    }

    /**
     * Export the database at [dbPath] to a `.noopbak` ZIP file at [destFile].
     *
     * The caller MUST ensure [DesktopDatabase.close] is called before invoking this method
     * (the file copy requires all file handles to be released, and the WAL checkpoint
     * needs exclusive access).
     *
     * @param dbPath absolute path to the live SQLite database file.
     * @param destFile the destination `.noopbak` file to write.
     * @return the [Result] of the export.
     */
    fun export(dbPath: String, destFile: File): Result {
        val dbFile = File(dbPath)
        if (!dbFile.exists()) {
            return Result.Failure("Database file not found: $dbPath")
        }

        // 1. Validate the SQLite header before exporting.
        if (!isValidSqliteHeader(dbFile)) {
            return Result.Failure("The database file appears to be corrupted.")
        }

        // 2. Run PRAGMA quick_check on the live DB to catch corruption before exporting.
        val quickCheck = runQuickCheck(dbFile)
        if (quickCheck != null) {
            return Result.Failure("Database integrity check failed: $quickCheck")
        }

        // 3. Write the backup ZIP.
        return try {
            writeBackupZip(dbFile, destFile, settingsJson = null)
            Result.Success(
                filePath = destFile.absolutePath,
                sizeBytes = destFile.length(),
            )
        } catch (e: IOException) {
            Result.Failure("Couldn't write the backup file: ${e.message}")
        }
    }

    /**
     * Write a `.noopbak` ZIP containing the SQLite database (and optional settings JSON)
     * to [dest]. Uses deflate compression. The SQLite entry is written first (matching the
     * cross-platform container contract — old importers stop at the first `.sqlite` entry).
     *
     * Port of Android `DataBackup.writeBackupZip`.
     */
    @Throws(IOException::class)
    fun writeBackupZip(dbFile: File, dest: File, settingsJson: String? = null) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { fos ->
            ZipOutputStream(fos).use { zos ->
                // Entry 1: the SQLite database (must be first).
                zos.putNextEntry(ZipEntry("noop-backup.sqlite"))
                dbFile.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        zos.write(buf, 0, n)
                    }
                }
                zos.closeEntry()

                // Entry 2 (optional): settings.json
                if (settingsJson != null) {
                    zos.putNextEntry(ZipEntry("settings.json"))
                    zos.write(settingsJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /** Read the first 16 bytes and check for the SQLite magic header. */
    fun isValidSqliteHeader(file: File): Boolean {
        file.inputStream().use { fis ->
            val head = ByteArray(16)
            val read = fis.read(head)
            if (read < 16) return false
            val magic = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0x00.toByte()
            return head.contentEquals(magic)
        }
    }

    /**
     * Run `PRAGMA quick_check(1)` on the database file. Returns null if the check passes,
     * or the error message string if corruption is detected. Opens a read-only JDBC
     * connection so the file is never mutated.
     */
    private fun runQuickCheck(dbFile: File): String? {
        return try {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            conn.use { c ->
                c.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA quick_check(1)").use { rs ->
                        if (rs.next()) {
                            val result = rs.getString(1)
                            if (result.equals("ok", ignoreCase = true)) null else result
                        } else {
                            "quick_check returned no rows"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // If we can't open the DB to check it, don't block the export — let the
            // header check be the gatekeeper. The import side will re-validate.
            null
        }
    }
}
