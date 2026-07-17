package com.noop.ble

import com.noop.protocol.DeviceFamily
import java.util.UUID

/**
 * Which strap the user is pairing. They pick this before scanning so we look for
 * exactly one device family instead of guessing — a WHOOP 4.0 scan no longer
 * waits forever on a WHOOP 5/MG wrist, and vice versa.
 *
 * This is the user-facing choice; it is deliberately separate from the
 * protocol-layer DeviceFamily (which carries CRC/characteristic detail).
 */
enum class WhoopModel(val displayName: String, val service: UUID) {
    WHOOP4("WHOOP 4.0", WhoopBleClient.WHOOP4_SERVICE),
    WHOOP5_MG("WHOOP 5.0 / MG", WhoopBleClient.WHOOP5_SERVICE);

    /**
     * The OTHER WHOOP family to try when a service-filtered scan for this model finds nothing. A
     * stale/missing persisted preference (after an update or restore) can point the scan at the wrong
     * service so it runs forever with the strap right there; rotating to the other family — and
     * persisting whichever one actually advertises — recovers reconnect automatically. Mirrors macOS
     * `WhoopModel.fallbackScanModel`. (PR#195)
     */
    val fallbackScanModel: WhoopModel
        get() = when (this) {
            WHOOP4 -> WHOOP5_MG
            WHOOP5_MG -> WHOOP4
        }
}

/**
 * Resolve the skin-temp raw→°C [DeviceFamily] (#938) from a paired device's persisted registry `model`
 * string, when the string CONFIDENTLY names one. A WHOOP 4.0 banks skin temp as a raw ADC that needs the
 * 4.0 transfer map; a 5/MG banks the /100 centidegree register.
 *
 * Returns null — a real "I don't know", not a default — when [model] doesn't confidently name either
 * family: a bare seeded "WHOOP", an EMPTY registry (a `.noopbak`-import install often has NO pairedDevice
 * row at all — verified on a real WHOOP 4.0 import), an unrecognized/blank model, or a non-WHOOP source.
 * Callers that need a concrete family fall back to the data-driven
 * [com.noop.analytics.AnalyticsEngine.inferSkinTempFamily], which classifies the device's OWN raw
 * skin-temp samples — the ONLY signal available when the registry can't identify the strap.
 *
 * The match accepts EVERY label the store actually holds for a WHOOP 4.0, which diverged across write
 * paths: the Android pairing wizard writes the SHORT label "4.0" (AddDeviceWizard.finishAdd), while the
 * Swift twin and a cross-platform `.noopbak` write the full "WHOOP 4.0" (Packages/WhoopStore). The prior
 * logic compared for EXACT equality with [WhoopModel.WHOOP4.displayName] ("WHOOP 4.0"), so on Android —
 * where the wizard writes "4.0" — the WHOOP4 branch never fired. We key off the "4.0"/"5.0" markers, so
 * an Oura "Ring 4" (no ".0") stays null and is never mis-scaled.
 */
fun whoopSkinTempFamily(model: String?): DeviceFamily? {
    val m = model?.trim()?.lowercase() ?: return null
    return when {
        m.contains("4.0") -> DeviceFamily.WHOOP4
        m.contains("5.0") -> DeviceFamily.WHOOP5
        else -> null
    }
}
