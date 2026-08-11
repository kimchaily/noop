package com.noop.data

import com.noop.data.MergeImport.Column
import com.noop.data.MergeImport.TablePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge import's safety decisions, pinned.
 *
 * The merge's promise is that it adds only what is missing and can never change or remove a row that
 * is already here. Half of that promise is carried by SQLite (`INSERT OR IGNORE` discards a colliding
 * row before it can touch a page) and needs no test of ours. The other half is [MergeImport.planTable]:
 * deciding WHICH tables may be copied at all and WHICH columns go across. That half is ours to get
 * wrong, so it is what gets pinned here.
 *
 * Android's SQLite is a throwing stub on the plain JVM, so the copy itself cannot run in a unit test.
 * That is why the decision was split out as a pure function over two schemas — the same split
 * [DataBackup.quickCheckVerdict] already uses for the integrity gate.
 */
class MergeImportPlanTest {

    /** The real `hrSample` shape: composite natural key, one payload column, one parity column. */
    private fun hrSampleColumns() = listOf(
        Column("deviceId", "TEXT", pk = 1),
        Column("ts", "INTEGER", pk = 2),
        Column("bpm", "INTEGER", pk = 0),
        Column("synced", "INTEGER", pk = 0),
    )

    private fun copyOf(plan: TablePlan): TablePlan.Copy =
        plan as? TablePlan.Copy ?: error("expected a Copy plan, got $plan")

    private fun skipReason(plan: TablePlan): String =
        (plan as? TablePlan.Skip ?: error("expected a Skip plan, got $plan")).reason

    // ── The ordinary case ───────────────────────────────────────────────────────────────────────

    @Test fun identicalSchemasCopyEveryColumn() {
        val plan = MergeImport.planTable(
            "hrSample", hrSampleColumns(), setOf("deviceId", "ts", "bpm", "synced"),
        )
        assertEquals(listOf("deviceId", "ts", "bpm", "synced"), copyOf(plan).columns)
    }

    @Test fun aLoneTextPrimaryKeyIsARealIdentityAndCopies() {
        // pairedDevice is keyed by a single TEXT deviceId. It must NOT be caught by the rowid rule
        // below - that would silently drop every device row from a merge.
        val plan = MergeImport.planTable(
            "pairedDevice",
            listOf(Column("deviceId", "TEXT", pk = 1), Column("model", "TEXT", pk = 0)),
            setOf("deviceId", "model"),
        )
        assertEquals(listOf("deviceId", "model"), copyOf(plan).columns)
    }

    // ── Column intersection: never invent, never assume ─────────────────────────────────────────

    @Test fun aColumnTheBackupLacksIsNotCopied() {
        // An older backup, made before `synced` existed. The rest still goes across; the missing
        // column keeps whatever default the live schema gives it.
        val plan = MergeImport.planTable(
            "hrSample", hrSampleColumns(), setOf("deviceId", "ts", "bpm"),
        )
        assertEquals(listOf("deviceId", "ts", "bpm"), copyOf(plan).columns)
    }

    @Test fun aColumnOnlyTheBackupHasIsNotCopied() {
        // A backup from a NEWER build carrying a column this one has never heard of. Copying it would
        // fail outright; the columns both sides know still merge.
        val plan = MergeImport.planTable(
            "hrSample", hrSampleColumns(),
            setOf("deviceId", "ts", "bpm", "synced", "somethingFromTheFuture"),
        )
        assertEquals(listOf("deviceId", "ts", "bpm", "synced"), copyOf(plan).columns)
        assertFalse("somethingFromTheFuture" in copyOf(plan).columns)
    }

    // ── The four refusals ───────────────────────────────────────────────────────────────────────

    @Test fun aMissingKeyColumnRefusesTheWholeTable() {
        // Without `ts` the rows have no identity, so OR IGNORE could not tell a duplicate from a new
        // row - and inserting with a null key would be worse than inserting nothing.
        val reason = skipReason(
            MergeImport.planTable("hrSample", hrSampleColumns(), setOf("deviceId", "bpm")),
        )
        assertTrue("names the missing key column: $reason", reason.contains("ts"))
        assertTrue("says why it matters: $reason", reason.contains("identifies a row"))
    }

    @Test fun aTableWithNoPrimaryKeyIsRefused() {
        val reason = skipReason(
            MergeImport.planTable(
                "scratch",
                listOf(Column("a", "TEXT", pk = 0), Column("b", "INTEGER", pk = 0)),
                setOf("a", "b"),
            ),
        )
        assertTrue(reason, reason.contains("no natural identity"))
    }

    @Test fun aLoneIntegerPrimaryKeyIsARowidAndIsRefused() {
        // An INTEGER PRIMARY KEY *is* the rowid: the same real-world row can carry a different number
        // in each install, so copying the numbers across would collide by accident and discard rows
        // that are genuinely new. Refuse and say so rather than guess.
        val reason = skipReason(
            MergeImport.planTable(
                "someLog",
                listOf(Column("id", "INTEGER", pk = 1), Column("text", "TEXT", pk = 0)),
                setOf("id", "text"),
            ),
        )
        assertTrue(reason, reason.contains("numbered per install"))
    }

    @Test fun caseDoesNotHelpARowidSneakThrough() {
        // SQLite reports declared types verbatim, so "integer" and "Integer" are both real.
        for (declared in listOf("integer", "Integer", "INTEGER")) {
            val plan = MergeImport.planTable(
                "someLog", listOf(Column("id", declared, pk = 1)), setOf("id"),
            )
            assertTrue("declared as $declared", plan is TablePlan.Skip)
        }
    }

    @Test fun aTableThisBuildDoesNotHaveIsRefused() {
        val reason = skipReason(
            MergeImport.planTable("futureTable", emptyList(), setOf("a"), liveHasTable = false),
        )
        assertTrue(reason, reason.contains("newer build"))
    }

    @Test fun noSharedColumnsAtAllIsRefused() {
        val reason = skipReason(
            MergeImport.planTable(
                "hrSample", hrSampleColumns(), setOf("totallyDifferent"),
            ),
        )
        // The missing-key rule fires first here and is the more specific complaint; either way the
        // table must not be copied.
        assertTrue(reason, reason.isNotBlank())
    }

    // ── Housekeeping ────────────────────────────────────────────────────────────────────────────

    @Test fun roomAndSqliteBookkeepingIsNeverMerged() {
        for (t in listOf("android_metadata", "room_master_table", "sqlite_sequence", "sqlite_stat1")) {
            assertTrue(t, MergeImport.isHousekeeping(t))
        }
    }

    @Test fun realTablesAreNotMistakenForHousekeeping() {
        for (t in listOf("hrSample", "dailyMetric", "sleepSession", "metricSeries")) {
            assertFalse(t, MergeImport.isHousekeeping(t))
        }
    }

    // ── The promise, restated as a property ─────────────────────────────────────────────────────

    @Test fun aCopyPlanNeverNamesAColumnEitherSideLacks() {
        val live = hrSampleColumns()
        val backup = setOf("deviceId", "ts", "bpm", "ghost")
        val copy = copyOf(MergeImport.planTable("hrSample", live, backup))
        val liveNames = live.map { it.name }.toSet()
        for (c in copy.columns) {
            assertTrue("$c must exist live", c in liveNames)
            assertTrue("$c must exist in the backup", c in backup)
        }
    }

    @Test fun everyCopyPlanCarriesTheFullKey() {
        // The property that makes OR IGNORE a real guarantee: if the key were incomplete, SQLite would
        // have nothing to collide on and every row would be inserted as new.
        val live = hrSampleColumns()
        val copy = copyOf(
            MergeImport.planTable("hrSample", live, setOf("deviceId", "ts", "bpm")),
        )
        for (key in live.filter { it.pk > 0 }.map { it.name }) {
            assertTrue("key column $key must be copied", key in copy.columns)
        }
    }
}
