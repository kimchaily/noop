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
 * string. Only a WHOOP 4.0 banks skin temp as a raw ADC that needs the 4.0 transfer map; every 5/MG and
 * every non-WHOOP source is read on the /100 centidegree default ([DeviceFamily.WHOOP5]).
 *
 * The match must accept EVERY label the store actually holds for a WHOOP 4.0, and those diverged across
 * write paths:
 *   - the Android pairing wizard writes the SHORT label "4.0" ([com.noop.ui] AddDeviceWizard.finishAdd),
 *   - the Swift twin and any cross-platform `.noopbak` restore write the full "WHOOP 4.0"
 *     (Packages/WhoopStore PairedDevice.swift).
 * The previous logic compared the model for EXACT equality with [WhoopModel.WHOOP4.displayName]
 * ("WHOOP 4.0"), so on Android — where the wizard writes "4.0" — the WHOOP4 branch never fired: every
 * 4.0 night's raw ADC was divided by 100 (~8 °C), fell below the 28 °C worn gate, and skin temp (plus the
 * illness signal it feeds) vanished. That is issue #938 reintroduced through a model-string mismatch, and
 * it also carries into an imported/migrated DB verbatim (a `.noopbak` is a whole-DB restore).
 *
 * We key STRICTLY off the "4.0" marker that BOTH real 4.0 labels share, rather than "not a 5": a WHOOP
 * 5.0 / MG ("5.0 MG", "WHOOP 5.0 / MG"), an Oura ("Oura Ring 4" — note NO ".0"), a bare seeded "WHOOP"
 * (genuinely family-ambiguous — resolved on connect, not here), a non-WHOOP import, or an absent model
 * all lack "4.0" and keep the /100 WHOOP5 default, so a stray/unknown label can never be mis-scaled as a
 * 4.0. Kept in lockstep with the Swift `IntelligenceEngine.skinTempFamily(forOwner:devices:)`.
 */
fun whoopSkinTempFamily(model: String?): DeviceFamily {
    val m = model?.trim()?.lowercase() ?: return DeviceFamily.WHOOP5
    return if (m.contains("4.0")) DeviceFamily.WHOOP4 else DeviceFamily.WHOOP5
}
