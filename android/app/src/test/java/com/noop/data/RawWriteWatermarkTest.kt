package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * #836, second pass — the change-detector the 15-minute backstop gates on.
 *
 * It used to be `"${countHr()}:${maxHrTs()}"`, and [HrFingerprintTest] pinned that shape. Both aggregates
 * walk the whole `hrSample` index (SQLite stores no row count, and `ts` is the second column of the only
 * index so the jump-to-maximum optimisation cannot apply), which made the gate cost more than the pass it
 * guarded — and grow with the entire recorded history rather than with the three weeks a pass reads.
 *
 * The replacement counts rows as they are written. The properties below are what the gate has to satisfy
 * to be a safe substitute; the headline one is [fingerprintTouchesNoDatabaseAtAll], which is the entire
 * reason the change exists.
 */
class RawWriteWatermarkTest {

    /** A DAO that throws on EVERY method — so any database access at all fails the test outright. */
    private fun explodingRepo(): WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("the gate must not query the database: ${method.name}")
        } as WhoopDao
        return WhoopRepository(dao)
    }

    @Test fun fingerprintTouchesNoDatabaseAtAll() {
        // The whole point: answering "did anything change?" must not read a single row. If this ever
        // starts calling the DAO again, the gate is back to costing more than the work it gates.
        val fp = explodingRepo().rawWriteFingerprint()
        assertNotEquals("", fp)
    }

    @Test fun anIdleStoreReadsTheSameEveryTime() {
        // Two ticks with nothing written in between must match, or the backstop would re-score forever.
        val repo = explodingRepo()
        assertEquals(repo.rawWriteFingerprint(), repo.rawWriteFingerprint())
    }

    @Test fun aWrittenRowMovesIt() {
        val before = RawWriteWatermark.current()
        RawWriteWatermark.recordWrites(1)
        assertNotEquals(before, RawWriteWatermark.current())
    }

    @Test fun rowsThatWereNotWrittenMoveNothing() {
        // A strap re-sending seconds the store already has: Room's IGNORE discards them, the caller
        // reports 0 inserted, and the gate must stay shut. Over-counting here would re-score on every
        // sync for nothing — the exact behaviour this work set out to remove.
        val before = RawWriteWatermark.current()
        RawWriteWatermark.recordWrites(0)
        RawWriteWatermark.recordWrites(-1)
        assertEquals(before, RawWriteWatermark.current())
    }

    @Test fun itKeepsMovingAcrossManyWrites() {
        // Distinctness matters more than the value: each batch has to look different from the last, or a
        // pass could be skipped after real data landed.
        val seen = HashSet<String>()
        seen.add(RawWriteWatermark.current())
        repeat(5) {
            RawWriteWatermark.recordWrites(3)
            seen.add(RawWriteWatermark.current())
        }
        assertEquals(6, seen.size)
    }

    @Test fun theValueIsOpaqueButStable() {
        // Callers may only compare it for equality. Pinning that it is non-blank and repeatable stops a
        // future edit from returning something that changes on every read (which would re-score forever).
        val a = RawWriteWatermark.current()
        val b = RawWriteWatermark.current()
        assertEquals(a, b)
        assertNotEquals("", a)
    }
}
