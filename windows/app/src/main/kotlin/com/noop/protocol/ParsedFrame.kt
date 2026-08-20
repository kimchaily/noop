package com.noop.protocol

/**
 * Result of decoding a single complete frame.
 *
 * Mirrors the Swift reference `ParsedFrame` reduced to the fields the Android app consumes:
 *  - [ok]: the envelope was well-formed (SOF present, minimum length). `false` for fragments.
 *  - [crcOk]: payload CRC32 outcome — `true`/`false` when verifiable, `null` when not enough bytes
 *    were present to check (mirrors Swift's optional `crcOK`). A non-`false` value is the integrity
 *    gate downstream code uses before trusting a frame.
 *  - [typeName]: canonical packet-type name (e.g. "REALTIME_DATA", "EVENT", "COMMAND_RESPONSE",
 *    "METADATA"), or "type{N}" / "INVALID/FRAGMENT" when unmapped/invalid.
 *  - [parsed]: a flat map of decoded fields. Values are plain Kotlin types (Int, Double, String,
 *    Boolean, or List<Int> for `rr_intervals`). Keys match the Swift parsed-dict keys exactly so
 *    higher layers (Streams, HistoricalMeta) port without renames.
 */
data class ParsedFrame(
    val ok: Boolean,
    val crcOk: Boolean?,
    val typeName: String,
    val parsed: Map<String, Any?>,
) {
    companion object {
        /** A frame that could not be decoded (too short, wrong SOF, or a mid-stream fragment). */
        fun invalid(): ParsedFrame =
            ParsedFrame(ok = false, crcOk = null, typeName = "INVALID/FRAGMENT", parsed = emptyMap())
    }
}
