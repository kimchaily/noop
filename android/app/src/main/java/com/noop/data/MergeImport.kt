package com.noop.data

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * "Fill in what's missing" import — the additive counterpart to [DataBackup.importFrom].
 *
 * The normal restore REPLACES the whole store: it is the right thing when moving to a new phone, and
 * the wrong thing when two installs each hold nights the other never saw (a strap that streamed to
 * Preview one night and to stable the next). Restoring either way round loses the other's nights.
 * This merges instead: every row the backup has and the live store lacks is added, and **no existing
 * row is ever changed or removed**.
 *
 * ## Why that guarantee holds
 *
 * Every row is written by an `INSERT OR IGNORE`. `OR IGNORE` means SQLite discards any row that would
 * violate a uniqueness constraint — and every table here is keyed by its natural identity (`deviceId`
 * plus a timestamp, or plus a day). So a row already present is not compared, not merged, not
 * preferred: its insert is thrown away by the database engine before it can touch a page, and
 * `executeInsert` returns -1 to say so. This is not a rule in Kotlin that a future edit could forget;
 * it is what the statement does. There is no UPDATE and no DELETE anywhere in this file.
 *
 * The backup is opened as a separate READ-ONLY connection and only ever read from. The rows are
 * written through Room's own live connection — deliberately NOT by attaching the backup and letting
 * SQLite copy table-to-table, which would be faster but which Android answers by silently dropping the
 * live database out of WAL mode for the rest of the session (`SQLiteDatabase.executeSql` disables
 * write-ahead logging on the first ATTACH). Trading a permanent concurrency regression for a shorter
 * import is not a trade worth making.
 *
 * ## What it deliberately will not do
 *
 * - **It will not merge a table whose rows have no natural identity.** A table keyed only by an
 *   auto-assigned integer id would let the same real-world row exist twice under two ids, and
 *   `OR IGNORE` would then either duplicate it or silently drop a genuinely new one. Such a table is
 *   skipped and named in the report rather than guessed at.
 * - **It will not invent columns.** Only columns present in BOTH schemas are copied. If a column the
 *   live schema needs for identity is missing from the backup, the table is skipped — inserting rows
 *   with a null key column would be worse than not inserting them.
 * - **It will not decide which of two versions of a day is better.** It only ever adds absent rows.
 *   Where both stores computed the same day, the live value stands. Re-scoring after the merge is
 *   what reconciles them, from the raw data both now share.
 */
object MergeImport {

    /** Room bookkeeping and SQLite internals — never merged, never reported. */
    private val HOUSEKEEPING = setOf("android_metadata", "sqlite_sequence", "room_master_table")

    /** Per-table outcome. [skippedReason] non-null means nothing was copied from this table. */
    data class TableResult(
        val table: String,
        val added: Long,
        val alreadyPresent: Long,
        val skippedReason: String? = null,
    )

    /** What a merge did, in full. Every table the backup carried appears exactly once. */
    data class Report(val tables: List<TableResult>) {
        val rowsAdded: Long get() = tables.sumOf { it.added }
        val rowsAlreadyPresent: Long get() = tables.sumOf { it.alreadyPresent }
        val skipped: List<TableResult> get() = tables.filter { it.skippedReason != null }

        /** The tables that actually gained rows, biggest first — what a user wants to read. */
        val gained: List<TableResult> get() = tables.filter { it.added > 0 }.sortedByDescending { it.added }

        /** One sentence for the banner. Honest about the nothing-to-do case, which is common. */
        val summary: String
            get() = when {
                rowsAdded == 0L && skipped.isEmpty() ->
                    "Nothing to add — this backup holds no measurements you don't already have."
                rowsAdded == 0L ->
                    "Nothing was added. ${skipped.size} table(s) couldn't be merged safely."
                else ->
                    "Added ${rowsAdded} measurement(s) that were missing. " +
                        "Nothing already on this phone was changed."
            }
    }

    sealed interface Result {
        data class Merged(val report: Report) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Merge the backup at [uri] into the live store.
     *
     * Runs the SAME validation gauntlet as a full restore — container staging, SQLite magic, origin
     * check, `PRAGMA quick_check` — deliberately reusing [DataBackup]'s own helpers rather than a
     * second copy, because two copies of an integrity gate is how they drift apart.
     *
     * Blocking; call it off the main thread. Unlike a full restore this needs NO app restart: Room's
     * connection stays open the whole time and the rows land under it.
     */
    fun mergeFrom(context: Context, uri: Uri): Result {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        val header = ByteArray(16)
        try {
            val read = resolver.openInputStream(uri)?.use { input ->
                var total = 0
                while (total < header.size) {
                    val n = input.read(header, total, header.size - total)
                    if (n <= 0) break
                    total += n
                }
                total
            } ?: return Result.Failed("Could not open the chosen file.")
            if (read < 4) return Result.Failed("That file is not a Choop backup.")
        } catch (e: IOException) {
            return Result.Failed("Could not read the chosen file: ${e.message}")
        }

        // Its own staging name, so a merge can never collide with a full restore staged in parallel.
        val staged = File(appContext.cacheDir, "merge-extract.sqlite")
        try {
            when (DataBackup.stageBackupSqlite(resolver.openInputStream(uri), header, staged)) {
                DataBackup.StageResult.OK -> Unit
                DataBackup.StageResult.CANNOT_OPEN ->
                    return Result.Failed("Could not open the chosen file.")
                DataBackup.StageResult.NO_DB_IN_ZIP ->
                    return fail(staged, "The backup archive doesn't contain a database file.")
                DataBackup.StageResult.NOT_A_BACKUP ->
                    return fail(staged, "That file is not a Choop backup.")
            }
        } catch (e: IOException) {
            return fail(staged, "Could not read the chosen file: ${e.message}")
        }

        if (!DataBackup.isValidSqliteHeader(staged)) {
            return fail(staged, "The backup archive doesn't contain a valid Choop database.")
        }

        val backupTables = DataBackup.sqliteTableNames(staged)
        when (DataBackup.backupOriginOf(backupTables)) {
            DataBackup.BackupOrigin.MAC -> return fail(
                staged,
                "This is a backup from the Mac or iOS Choop app. Its database is laid out differently, " +
                    "so its rows can't be merged into this one. Export the WHOOP-format CSV there and " +
                    "import that instead.",
            )
            DataBackup.BackupOrigin.UNKNOWN ->
                if (DataBackup.holdsData(backupTables)) {
                    return fail(
                        staged,
                        "This isn't a Choop backup from this app — it looks like another app's database.",
                    )
                }
            DataBackup.BackupOrigin.ANDROID -> Unit
        }

        DataBackup.sqliteQuickCheckFailure(staged)?.let { complaint ->
            return fail(
                staged,
                "This backup file is damaged, so nothing was merged (SQLite reports: $complaint). " +
                    "Your data is untouched. Try an earlier backup file.",
            )
        }

        val db = WhoopDatabase.get(appContext).openHelper.writableDatabase
        val backup = runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                staged.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                DataBackup.PRESERVE_ON_CORRUPTION,
            )
        }.getOrElse { return fail(staged, "Could not read the backup: ${it.message}") }

        return try {
            Result.Merged(copyMissing(db, backup, backupTables))
        } catch (e: Exception) {
            Result.Failed("The merge stopped early: ${e.message}. Everything copied before that is kept.")
        } finally {
            runCatching { backup.close() }
            staged.delete()
        }
    }

    // ── The copy itself ─────────────────────────────────────────────────────────────────────────

    /** Rows per transaction. Big enough that the commit cost disappears, small enough that a kill
     *  mid-merge loses at most this many rows of work — none of which were needed, since a re-run
     *  simply adds them again. */
    private const val BATCH = 10_000

    /**
     * What to do with one table, decided PURELY from the two schemas — no database, no rows.
     *
     * Split out because this is where every safety decision lives: whether a table can be merged at
     * all, and which columns may be copied. The copy itself is one `INSERT OR IGNORE`, whose behaviour
     * is SQLite's rather than ours. Android's SQLite is a throwing stub on the plain JVM, so the copy
     * cannot be unit-tested here — but these decisions can, and they are the part that could be got
     * wrong. (Same split the integrity gate already uses: `DataBackup.quickCheckVerdict` is pure and
     * pinned, while the `PRAGMA` around it is not.)
     */
    sealed interface TablePlan {
        /** Copy [columns] (present in both schemas) from this table. */
        data class Copy(val table: String, val columns: List<String>) : TablePlan

        /** Copy nothing, and tell the user [reason] rather than guessing. */
        data class Skip(val table: String, val reason: String) : TablePlan
    }

    /** One column as `PRAGMA table_info` describes it. [pk] is 0 for a non-key column. */
    data class Column(val name: String, val type: String, val pk: Int)

    /**
     * Decide what to do with [table], given the live schema's [liveColumns] and the column names the
     * backup has for it ([backupColumns], empty when the backup lacks the table).
     *
     * [liveHasTable] false means this build has no such table at all — a backup from a newer version.
     */
    fun planTable(
        table: String,
        liveColumns: List<Column>,
        backupColumns: Set<String>,
        liveHasTable: Boolean = true,
    ): TablePlan {
        if (!liveHasTable) {
            return TablePlan.Skip(
                table, "this version of Choop has no such table - the backup is from a newer build",
            )
        }
        val shared = liveColumns.filter { it.name in backupColumns }
        val keyCols = liveColumns.filter { it.pk > 0 }
        val reason = when {
            keyCols.isEmpty() ->
                "its rows carry no natural identity, so a duplicate couldn't be told from a new row"
            // A lone INTEGER primary key IS the rowid: the same real row can hold a different number in
            // each store, so copying the numbers across would collide by accident and discard rows that
            // are genuinely new.
            keyCols.size == 1 && keyCols[0].type.equals("INTEGER", ignoreCase = true) ->
                "its rows are numbered per install, so the same row can carry different numbers here"
            keyCols.any { it.name !in backupColumns } ->
                "the backup is missing " +
                    keyCols.filter { it.name !in backupColumns }.joinToString { it.name } +
                    ", which identifies a row"
            shared.isEmpty() -> "the backup shares no columns with this version"
            else -> null
        }
        return if (reason != null) TablePlan.Skip(table, reason)
        else TablePlan.Copy(table, shared.map { it.name })
    }

    /** True for a table this merge never touches or reports (Room bookkeeping, SQLite internals). */
    fun isHousekeeping(table: String): Boolean =
        table in HOUSEKEEPING || table.startsWith("sqlite_")

    private fun copyMissing(
        db: SupportSQLiteDatabase,
        backup: android.database.sqlite.SQLiteDatabase,
        backupTables: Set<String>,
    ): Report {
        val liveTables = tableNames(db)
        val results = ArrayList<TableResult>()

        for (table in backupTables.sorted()) {
            if (isHousekeeping(table)) continue
            val plan = planTable(
                table = table,
                liveColumns = if (table in liveTables) liveColumns(db, table) else emptyList(),
                backupColumns = backupColumns(backup, table),
                liveHasTable = table in liveTables,
            )
            results += when (plan) {
                is TablePlan.Skip -> TableResult(table, 0, 0, plan.reason)
                is TablePlan.Copy -> copyTable(db, backup, table, plan.columns)
            }
        }
        return Report(results)
    }

    private fun copyTable(
        db: SupportSQLiteDatabase,
        backup: android.database.sqlite.SQLiteDatabase,
        table: String,
        cols: List<String>,
    ): TableResult {
        val list = cols.joinToString(", ") { "`$it`" }
        val holes = cols.joinToString(", ") { "?" }
        // The guarantee, in one clause: OR IGNORE means an insert whose key already exists is
        // discarded by SQLite before it can touch the stored row, and reports itself as -1.
        val insert = db.compileStatement("INSERT OR IGNORE INTO `$table` ($list) VALUES ($holes)")
        var added = 0L
        var seen = 0L
        try {
            backup.rawQuery("SELECT $list FROM `$table`", null).use { c ->
                var inBatch = 0
                db.beginTransaction()
                try {
                    while (c.moveToNext()) {
                        insert.clearBindings()
                        for (i in cols.indices) {
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> insert.bindNull(i + 1)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i + 1, c.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(i + 1, c.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(i + 1, c.getBlob(i))
                                else -> insert.bindString(i + 1, c.getString(i))
                            }
                        }
                        // -1 means OR IGNORE dropped it: the row was already here, untouched.
                        if (insert.executeInsert() != -1L) added++
                        seen++
                        if (++inBatch >= BATCH) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                            inBatch = 0
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            runCatching { insert.close() }
        }
        return TableResult(table, added, (seen - added).coerceAtLeast(0L))
    }

    // ── Schema probes ───────────────────────────────────────────────────────────────────────────

    private fun liveColumns(db: SupportSQLiteDatabase, table: String): List<Column> {
        val out = ArrayList<Column>()
        db.query("PRAGMA table_info(`$table`)").use { c -> readColumns(c, out) }
        return out
    }

    private fun backupColumns(
        backup: android.database.sqlite.SQLiteDatabase,
        table: String,
    ): Set<String> {
        val out = ArrayList<Column>()
        backup.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> readColumns(c, out) }
        return out.map { it.name }.toSet()
    }

    private fun readColumns(c: android.database.Cursor, into: MutableList<Column>) {
        val iName = c.getColumnIndex("name")
        val iType = c.getColumnIndex("type")
        val iPk = c.getColumnIndex("pk")
        if (iName < 0 || iPk < 0) return
        while (c.moveToNext()) {
            into += Column(
                name = c.getString(iName),
                type = if (iType >= 0) c.getString(iType).orEmpty() else "",
                pk = c.getInt(iPk),
            )
        }
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val out = LinkedHashSet<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) c.getString(0)?.let(out::add)
        }
        return out
    }

    private fun fail(staged: File, message: String): Result {
        staged.delete()
        return Result.Failed(message)
    }
}
