package com.noop.data

/**
 * Result of importing an external data source (WHOOP export, Apple Health export, etc.)
 * into the local store. Returned by every importer so the UI can show one consistent
 * "imported N days / M workouts" message.
 *
 * Desktop port of the Android ImportSummary — identical field names and semantics so
 * call sites stay source-compatible.
 */
data class ImportSummary(
    /** Human label of the source: "WHOOP", "Apple Health", etc. */
    val source: String,
    /** Rows actually upserted, keyed by table name (e.g. "dailyMetric" -> 1200). */
    val counts: Map<String, Int>,
    /** Earliest day touched, "YYYY-MM-DD" (null if nothing imported). */
    val firstDay: String? = null,
    /** Latest day touched, "YYYY-MM-DD". */
    val lastDay: String? = null,
    /** One-line human summary for a status line. */
    val message: String,
) {
    val totalRows: Int get() = counts.values.sum()

    companion object {
        /** A failed/empty import carrying a reason. */
        fun failure(source: String, reason: String) =
            ImportSummary(source = source, counts = emptyMap(), message = reason)
    }
}
