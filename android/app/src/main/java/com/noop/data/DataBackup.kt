package com.noop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-store EXPORT / IMPORT for device migration.
 *
 * NOOP keeps everything on-device in a single Room/SQLite file ([WhoopDatabase.DB_NAME]).
 * Moving to a new phone therefore means moving exactly that one file. There is no cloud,
 * no account, nothing leaves the device except through these two explicit, user-driven
 * file operations (a SAF document the user picks).
 *
 * Export: checkpoint the WAL into the main db file, then write a single-entry ZIP
 * (the `.noopbak` format) containing the SQLite file. ZIP deflate typically reduces a
 * 100 MB+ SQLite backup to 10–20 MB — SQLite's page-aligned text data compresses very
 * well. The ZIP is a standard container: users can rename `.noopbak` → `.zip` and
 * extract the SQLite manually with any archive tool on any OS.
 *
 * Import: detect whether the picked file is a `.noopbak` ZIP (PK magic) or a legacy
 * plain `.sqlite` / `.noopdb` (SQLite magic) and handle both, so old backups keep
 * working. Validates the extracted/direct SQLite header before touching the live DB.
 * Closes the live Room singleton, snapshots the current db, overwrites it with the
 * chosen one, and drops the stale `-wal` / `-shm` sidecars. The caller then instructs
 * the user to restart the app so Room re-opens the new file fresh.
 */
object DataBackup {

    /** Entry name inside the `.noopbak` ZIP. */
    private const val ZIP_ENTRY_NAME = "noop-backup.sqlite"

    /** First 16 bytes of every SQLite 3 file: "SQLite format 3\0". */
    private val SQLITE_MAGIC: ByteArray =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

    /** First 4 bytes of every ZIP file: "PK\x03\x04". */
    private val ZIP_MAGIC: ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Outcome of an [importFrom] call. On success the app must be restarted. */
    sealed interface ImportResult {
        /** The new database is in place; tell the user to relaunch NOOP. */
        data object NeedsRestart : ImportResult

        /** Import failed and the original database is untouched. */
        data class Failed(val message: String) : ImportResult
    }

    /**
     * Export the live database to [uri] as a compressed `.noopbak` (single-entry ZIP).
     *
     * Runs `PRAGMA wal_checkpoint(TRUNCATE)` first so the db file is fully consistent.
     * The ZIP uses deflate compression; typical reduction is 80–90% vs the raw SQLite.
     * Throws on failure so the caller can surface the message in a toast/snackbar.
     */
    @Throws(IOException::class)
    fun exportTo(context: Context, uri: Uri) {
        val appContext = context.applicationContext

        // Fold the WAL back into the main file so the snapshot is complete.
        val db = WhoopDatabase.get(appContext)
        db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            cursor.moveToFirst()
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        if (!dbFile.exists()) {
            throw IOException("No database to export yet.")
        }

        val resolver = appContext.contentResolver
        val output = resolver.openOutputStream(uri)
            ?: throw IOException("Could not open the chosen file for writing.")
        output.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                dbFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Replace the live database with the backup at [uri].
     *
     * Accepts both the new `.noopbak` (ZIP) format and legacy plain `.sqlite`/`.noopdb`
     * files so older backups keep working after the format upgrade.
     *
     * On any error the current database is left exactly as it was. On success the caller
     * MUST instruct the user to fully restart the app.
     */
    fun importFrom(context: Context, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        // 1. Peek at the first 16 bytes to distinguish ZIP from plain SQLite.
        val header = ByteArray(16)
        try {
            val read = resolver.openInputStream(uri)?.use { readFully(it, header) }
                ?: return ImportResult.Failed("Could not open the chosen file.")
            if (read < 4) return ImportResult.Failed("That file is not a NOOP backup.")
        } catch (e: IOException) {
            return ImportResult.Failed("Could not read the chosen file: ${e.message}")
        }

        // 2. If it's a ZIP (.noopbak), extract the SQLite entry to a temp file.
        //    If it's a plain SQLite (legacy), copy it to the same temp file.
        //    The container-staging step is factored into [stageBackupSqlite] (a pure file/stream
        //    function) so it can be exercised under real file I/O in unit tests without Room/Context.
        val tempSqlite = File(appContext.cacheDir, "import-extract.sqlite")
        try {
            when (val staged = stageBackupSqlite(resolver.openInputStream(uri), header, tempSqlite)) {
                StageResult.OK -> Unit
                StageResult.CANNOT_OPEN -> return ImportResult.Failed("Could not open the chosen file.")
                StageResult.NO_DB_IN_ZIP -> return ImportResult.Failed(
                    "The backup archive doesn't contain a database file."
                )
                StageResult.NOT_A_BACKUP -> return ImportResult.Failed(
                    "That file is not a NOOP backup - it doesn't look like a .noopbak archive or a SQLite database."
                )
                else -> error("unreachable stage result $staged")
            }
        } catch (e: IOException) {
            tempSqlite.delete()
            return ImportResult.Failed("Could not read the chosen file: ${e.message}")
        }

        // 3. Validate the extracted file is a real SQLite database (magic-byte check).
        if (!isValidSqliteHeader(tempSqlite)) {
            tempSqlite.delete()
            return ImportResult.Failed("The backup archive doesn't contain a valid NOOP database.")
        }

        // 3b. Origin check (parity with the Apple side's GRDB-origin rejection). The SQLite magic
        //     passes for ANY SQLite file: a GRDB (Mac/iOS NOOP) backup or some other app's database
        //     would otherwise sail through and REPLACE the live Room store, stranding the user. Read
        //     the backup's table names READ-ONLY and reject anything that isn't a Room (this-app)
        //     backup but still holds real data. Empty/pre-migration files fall through to Room's
        //     open-time migrator, exactly as before.
        val backupTables = sqliteTableNames(tempSqlite)
        when (backupOriginOf(backupTables)) {
            BackupOrigin.MAC ->
                return rejectForeign(
                    tempSqlite,
                    "This isn't a NOOP backup from this app. It looks like a backup from the Mac or " +
                        "iOS NOOP app (it carries that platform's migration bookkeeping). Restoring it here " +
                        "would strand your store. To move your history across platforms, export the " +
                        "WHOOP-format CSV on the other device (Settings → Export data) and import that here.",
                )
            BackupOrigin.UNKNOWN ->
                if (holdsData(backupTables)) {
                    return rejectForeign(
                        tempSqlite,
                        "This isn't a NOOP backup from this app. It's missing the database bookkeeping a " +
                            "NOOP backup carries (it looks like another app's database). Restoring it would " +
                            "strand your store.",
                    )
                }
            BackupOrigin.ANDROID -> Unit // our own backup, proceed.
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        val walFile = File(dbFile.path + "-wal")
        val shmFile = File(dbFile.path + "-shm")
        val rollbackFile = File(dbFile.path + ".import-bak")

        // 4. Close the live Room singleton so the file handles are released.
        WhoopDatabase.close()

        // 5. Snapshot the current db so a failed copy can be rolled back.
        try {
            rollbackFile.delete()
            if (dbFile.exists()) dbFile.copyTo(rollbackFile, overwrite = true)
        } catch (e: IOException) {
            tempSqlite.delete()
            return ImportResult.Failed("Could not back up the current data: ${e.message}")
        }

        // 6. Overwrite the db file with the extracted backup, then drop the stale sidecars.
        try {
            dbFile.parentFile?.mkdirs()
            tempSqlite.copyTo(dbFile, overwrite = true)
            walFile.delete()
            shmFile.delete()
        } catch (e: IOException) {
            runCatching { if (rollbackFile.exists()) rollbackFile.copyTo(dbFile, overwrite = true) }
            rollbackFile.delete()
            tempSqlite.delete()
            return ImportResult.Failed("Import failed, your data is unchanged: ${e.message}")
        }

        rollbackFile.delete()
        tempSqlite.delete()
        return ImportResult.NeedsRestart
    }

    // ── Container staging (pure file/stream layer, unit-tested under real file I/O) ──────

    /** Outcome of [stageBackupSqlite]: the SQLite was staged, or why it wasn't. */
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP }

    /**
     * Stage the SQLite payload of a backup into [dest], from an already-opened [input] stream whose
     * first bytes are [header]. Handles both the `.noopbak` ZIP (extract the `.sqlite` entry) and a
     * legacy plain SQLite (copy through). Closes [input]. Context-free + stream-driven so the unit
     * tests drive it with real `java.util.zip` archives and real files, exercising the exact extraction
     * the live import uses (no behaviour fork between test and production).
     *
     * NOTE this does NOT validate the staged file's SQLite header or origin; [importFrom] does that
     * next, on the staged file. Keeping staging and validation separate keeps each pure-testable.
     */
    fun stageBackupSqlite(input: java.io.InputStream?, header: ByteArray, dest: File): StageResult {
        if (input == null) return StageResult.CANNOT_OPEN
        input.use { stream ->
            when {
                header.startsWith(ZIP_MAGIC) -> {
                    var found = false
                    ZipInputStream(stream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".sqlite")) {
                                FileOutputStream(dest).use { out -> zip.copyTo(out) }
                                found = true
                                break
                            }
                            entry = zip.nextEntry
                        }
                    }
                    return if (found) StageResult.OK else StageResult.NO_DB_IN_ZIP
                }
                header.startsWith(SQLITE_MAGIC) -> {
                    FileOutputStream(dest).use { out -> stream.copyTo(out) }
                    return StageResult.OK
                }
                else -> return StageResult.NOT_A_BACKUP
            }
        }
    }

    /** Write [dbFile]'s bytes into a single-entry deflate ZIP at [dest] (the `.noopbak` container).
     *  Context-free twin of the stream the live [exportTo] writes, so tests round-trip a real archive. */
    @Throws(IOException::class)
    fun writeBackupZip(dbFile: File, dest: File) {
        FileOutputStream(dest).use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                dbFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** True when [file] begins with the SQLite 3 magic. Pure; used by [importFrom] and the tests. */
    fun isValidSqliteHeader(file: File): Boolean {
        val buf = ByteArray(SQLITE_MAGIC.size)
        return runCatching {
            val read = file.inputStream().use { readFully(it, buf) }
            read >= SQLITE_MAGIC.size && buf.contentEquals(SQLITE_MAGIC)
        }.getOrDefault(false)
    }

    /** First [n] bytes of [file] (or fewer at EOF): the header peek the import does on the raw file. */
    fun peekHeader(file: File, n: Int = 16): ByteArray {
        val buf = ByteArray(n)
        val read = runCatching { file.inputStream().use { readFully(it, buf) } }.getOrDefault(0)
        return buf.copyOf(read)
    }

    /** Read up to [buffer].size bytes from [input], looping over short reads. Returns bytes read. */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    /** True when [this] begins with every byte in [prefix]. */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    // ── Origin validation (parity with the Apple GRDB-origin rejection) ─────────

    /** Which platform produced a NOOP backup, judged by its migrator's bookkeeping table. */
    enum class BackupOrigin { MAC, ANDROID, UNKNOWN }

    /**
     * Pure classification over a backup's `sqlite_master` table names: Room (this app) writes
     * `room_master_table`; GRDB (the Mac/iOS app) writes `grdb_migrations`. `.UNKNOWN` (neither, an
     * empty or pre-migration file) falls through to the normal import path, where Room's open-time
     * migrator decides. Mirrors the Apple `DataBackup.backupOrigin(of:)` so both platforms agree
     * byte-for-byte on what a foreign backup is.
     *
     * This platform's marker wins on the (degenerate) both-present case: restoring our own store here
     * is the less destructive read.
     */
    fun backupOriginOf(tableNames: Set<String>): BackupOrigin {
        if (tableNames.contains("room_master_table")) return BackupOrigin.ANDROID
        if (tableNames.contains("grdb_migrations")) return BackupOrigin.MAC
        // Older Room layouts didn't carry `room_master_table`; treat the Room/AndroidX pairing of
        // `android_metadata` + `sqlite_sequence` as one of ours too (mirrors the Apple side, which
        // reads that same duo as Android).
        if (tableNames.contains("android_metadata") && tableNames.contains("sqlite_sequence")) {
            return BackupOrigin.ANDROID
        }
        return BackupOrigin.UNKNOWN
    }

    /**
     * Does this backup actually hold app data (vs an empty/fresh file)? True when it carries any
     * user-content table beyond the SQLite/Android housekeeping ones. An `.UNKNOWN` file with no
     * content is harmless to restore; one WITH content but no recognised bookkeeping is some other
     * app's database and is rejected.
     */
    fun holdsData(tableNames: Set<String>): Boolean {
        val housekeeping = setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")
        return tableNames.any { it !in housekeeping && !it.startsWith("sqlite_") }
    }

    /** Every table name in [file], opened READ-ONLY so the probed file is never mutated. Empty on failure. */
    private fun sqliteTableNames(file: File): Set<String> {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return emptySet()
        return try {
            val names = LinkedHashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let(names::add)
            }
            names
        } catch (e: Exception) {
            emptySet()
        } finally {
            runCatching { db.close() }
        }
    }

    /** Delete the staged temp file and return a Failed result, keeping the live DB untouched. */
    private fun rejectForeign(tempSqlite: File, message: String): ImportResult {
        tempSqlite.delete()
        return ImportResult.Failed(message)
    }
}
