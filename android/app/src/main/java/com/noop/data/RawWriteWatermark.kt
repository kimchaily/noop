package com.noop.data

import java.util.concurrent.atomic.AtomicLong

/**
 * "Has any raw measurement actually landed since the last completed scoring pass?" — answered without
 * touching the database.
 *
 * ## What this replaces, and why
 *
 * The 15-minute backstop used to answer that question with
 * `SELECT COUNT(*) FROM hrSample` plus `SELECT MAX(ts) FROM hrSample`. Both look cheap and neither is:
 *
 *  - SQLite keeps no stored row count, so an unqualified `COUNT(*)` visits every index entry.
 *  - `hrSample` is indexed on `(deviceId, ts)`. SQLite's jump-to-the-extreme optimisation only applies
 *    when the column is the index's FIRST — so an unqualified `MAX(ts)` cannot use it either and walks
 *    the whole index as well.
 *
 * So the gate that exists to avoid work scanned the entire recorded history, twice, every fifteen
 * minutes — while the work it was gating only ever touches the trailing three weeks. The check cost
 * more than the thing it was protecting, and unlike that thing it grows for as long as the app is used.
 *
 * ## Why counting up is not just cheaper but MORE accurate
 *
 * The old pair could miss a real change: a night that finishes uploading hours late inserts rows with
 * older timestamps, so `MAX(ts)` does not move — leaving `COUNT(*)` as the only witness, and only
 * because the row total happened to change. This counts the rows that were genuinely written, so late
 * arrivals are caught by construction rather than by coincidence.
 *
 * It also watches EVERY raw stream, not just heart rate. The old gate counted `hrSample` alone, which
 * on a WHOOP 5/MG can stay empty for ever: its v26 records carry no per-second heart rate at all, so
 * the derived beats land in `ppgHrSample` and the gate would never open.
 *
 * ## Why a restart is safe
 *
 * The count lives in memory, so it resets when the process does — and the stored watermark from the
 * previous run will not match, because [processTag] is fresh. That difference forces exactly ONE pass
 * after a restart, which is the direction to err in: an unnecessary pass costs a little work, a missed
 * one costs a stale score with nothing left to trigger its repair.
 *
 * Counting only rows that were REALLY inserted (Room returns -1 for a row `OnConflictStrategy.IGNORE`
 * discarded) keeps the old precision: re-offloading seconds the store already has moves nothing, so a
 * strap re-sending known data still does not trigger a pass.
 */
object RawWriteWatermark {

    /** Distinct per process, so a fresh process can never match a watermark written by an older one. */
    private val processTag: String = java.lang.Long.toHexString(System.nanoTime())

    private val writes = AtomicLong(0L)

    /**
     * Record that [insertedRows] raw rows were genuinely written. Pass the count of ACTUAL inserts
     * (Room's -1 entries excluded), never the attempted size — over-counting would make a strap that
     * re-sends known seconds look like a change and re-score for nothing.
     */
    fun recordWrites(insertedRows: Int) {
        if (insertedRows > 0) writes.addAndGet(insertedRows.toLong())
    }

    /**
     * The current value to compare against the stored watermark. Opaque on purpose: callers must only
     * ever test it for equality, never parse it or reason about its parts.
     */
    fun current(): String = "$processTag:${writes.get()}"
}
