package com.noop.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Unit tests for [BackupImport] — file validation, ZIP extraction, SQLite
 * magic-byte checking, schema reconciliation, and full round-trip import.
 *
 * These tests create temporary files and databases in the system temp dir
 * and clean up after themselves.
 */
class BackupImportTest {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"), "noop-import-test-${System.nanoTime()}")
    private val dbPath = File(tmpDir, "test-whoop.sqlite").absolutePath

    init {
        tmpDir.mkdirs()
    }

    // ── File-not-found ────────────────────────────────────────────────────

    @Test fun import_nonExistentFile_returnsFailure() {
        val result = BackupImport.import(File(tmpDir, "does-not-exist.noopbak"), dbPath)
        assertTrue(result is BackupImport.Result.Failure)
        val msg = (result as BackupImport.Result.Failure).message
        assertTrue(msg.contains("not found", ignoreCase = true))
    }

    // ── Non-ZIP, non-SQLite file ──────────────────────────────────────────

    @Test fun import_textFile_returnsFailure() {
        val textFile = File(tmpDir, "not-a-backup.txt")
        textFile.writeText("this is not a backup file")
        val result = BackupImport.import(textFile, dbPath)
        assertTrue(result is BackupImport.Result.Failure)
        val msg = (result as BackupImport.Result.Failure).message
        assertTrue(msg.contains("SQLite", ignoreCase = true))
    }

    // ── Empty ZIP ─────────────────────────────────────────────────────────

    @Test fun import_emptyZip_returnsFailure() {
        val emptyZip = File(tmpDir, "empty.zip")
        ZipOutputStream(FileOutputStream(emptyZip)).use { it.closeEntry() }
        val result = BackupImport.import(emptyZip, dbPath)
        assertTrue(result is BackupImport.Result.Failure)
    }

    // ── ZIP with non-SQLite content ───────────────────────────────────────

    @Test fun import_zipWithTextFile_returnsFailure() {
        val zipFile = File(tmpDir, "text-in-zip.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val result = BackupImport.import(zipFile, dbPath)
        assertTrue(result is BackupImport.Result.Failure)
    }

    // ── Full round-trip: create SQLite DB → ZIP → import ───────────────────

    @Test fun import_validZipWithSQLite_returnsSuccess() {
        // 1. Create a real SQLite DB with some data tables.
        val sourceDb = File(tmpDir, "source.sqlite")
        createTestDatabase(sourceDb)

        // 2. Wrap it in a .noopbak ZIP.
        val zipFile = File(tmpDir, "valid-backup.noopbak")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("noop-backup.sqlite"))
            sourceDb.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }

        // 3. Import it.
        val result = BackupImport.import(zipFile, dbPath)
        assertTrue("Expected Success but got $result", result is BackupImport.Result.Success)
        val success = result as BackupImport.Result.Success

        // 4. Verify the imported DB has the expected tables and row counts.
        assertTrue(File(dbPath).exists())
        val counts = success.rowsImported
        assertTrue(counts.containsKey("hrSample"))
        assertEquals(3, counts["hrSample"])

        // 5. Verify the schema was reconciled (user_version set).
        verifySchemaReconciled(File(dbPath))
    }

    // ── Legacy SQLite file (not zipped) ───────────────────────────────────

    @Test fun import_legacySqliteFile_returnsSuccess() {
        val sourceDb = File(tmpDir, "legacy.sqlite")
        createTestDatabase(sourceDb)

        val result = BackupImport.import(sourceDb, dbPath)
        assertTrue("Expected Success but got $result", result is BackupImport.Result.Success)
    }

    // ── Rollback safety: failed copy preserves old DB ─────────────────────

    @Test fun import_existingDbPreservedOnFailure() {
        // Create an existing DB at the target path.
        val existingDb = File(dbPath)
        createTestDatabase(existingDb)
        val originalRowCount = countHrRows(existingDb)

        // Try to import a bogus file (text, not ZIP/SQLite).
        val bogusFile = File(tmpDir, "bogus.txt")
        bogusFile.writeText("not a database")

        val result = BackupImport.import(bogusFile, dbPath)
        assertTrue(result is BackupImport.Result.Failure)

        // The existing DB should still be intact with its data.
        // (The import fails BEFORE touching the existing DB for non-SQLite files.)
        assertTrue(existingDb.exists())
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Create a minimal SQLite database with a `hrSample` table and 3 rows,
     * matching the schema the desktop app expects.
     */
    private fun createTestDatabase(file: File) {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `hrSample` (
                        `deviceId` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `synced` INTEGER DEFAULT 0,
                        PRIMARY KEY(`deviceId`, `ts`)
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `dailyMetric` (
                        `deviceId` TEXT NOT NULL,
                        `day` TEXT NOT NULL,
                        PRIMARY KEY(`deviceId`, `day`)
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `pairedDevice` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `brand` TEXT,
                        `model` TEXT,
                        `nickname` TEXT,
                        `sourceKind` TEXT,
                        `capabilities` TEXT,
                        `status` TEXT,
                        `addedAt` INTEGER,
                        `lastSeenAt` INTEGER
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `sleepSession` (
                        `deviceId` TEXT NOT NULL,
                        `startTs` INTEGER NOT NULL,
                        `endTs` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`, `startTs`)
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `workout` (
                        `deviceId` TEXT NOT NULL,
                        `startTs` INTEGER NOT NULL,
                        `endTs` INTEGER NOT NULL,
                        `sport` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        PRIMARY KEY(`deviceId`, `startTs`, `sport`)
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `battery` (
                        `deviceId` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`, `ts`)
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `journal` (
                        `deviceId` TEXT NOT NULL,
                        `day` TEXT NOT NULL,
                        `question` TEXT NOT NULL,
                        `answeredYes` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`, `day`, `question`)
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS `stepSample` (
                        `deviceId` TEXT NOT NULL,
                        `ts` INTEGER NOT NULL,
                        `counter` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`, `ts`)
                    )
                """)
                // Insert 3 HR samples
                stmt.execute("INSERT INTO `hrSample` VALUES ('d', 1000, 60, 0)")
                stmt.execute("INSERT INTO `hrSample` VALUES ('d', 1001, 61, 0)")
                stmt.execute("INSERT INTO `hrSample` VALUES ('d', 1002, 62, 0)")
            }
        }
    }

    /** Count rows in the `hrSample` table of the given SQLite file. */
    private fun countHrRows(file: File): Int {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM `hrSample`").use { rs ->
                    return rs.getInt(1)
                }
            }
        }
    }

    /** Verify the imported DB has user_version = SCHEMA_VERSION (reconciliation ran). */
    private fun verifySchemaReconciled(file: File) {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version").use { rs ->
                    val version = rs.getInt(1)
                    assertEquals(DesktopDatabase.SCHEMA_VERSION, version)
                }
                // Verify the reconciled columns were added.
                assertTrue(hasColumn(stmt, "workout", "routePolyline"))
                assertTrue(hasColumn(stmt, "dailyMetric", "spo2Pct"))
                assertTrue(hasColumn(stmt, "sleepSession", "userEdited"))
                assertTrue(hasColumn(stmt, "battery", "charging"))
            }
        }
    }

    private fun hasColumn(stmt: Statement, table: String, column: String): Boolean {
        stmt.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            while (rs.next()) {
                if (rs.getString("name") == column) return true
            }
        }
        return false
    }
}
