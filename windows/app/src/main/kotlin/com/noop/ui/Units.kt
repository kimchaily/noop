package com.noop.ui

import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Unit system preference
//
// Choop stores EVERYTHING in SI (km, kg, cm, degC) — the importers normalise on the way in, so this
// is a purely cosmetic, display-only layer. There is no data migration when the user flips this. One
// Metric/Imperial switch for length+mass with a SEPARATE temperature override, because plenty of
// people think in kg/cm but still read body temperature in degF (and vice versa). Default is Metric.

/** Length+mass unit system. Temperature has its own override (see [UnitPrefs.temperature]). */
enum class UnitSystem(val raw: String) {
    METRIC("metric"),
    IMPERIAL("imperial");

    /** Pairs temperature with the length/mass choice when no explicit override is set. */
    val temperatureMatching: TemperatureUnit
        get() = if (this == IMPERIAL) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS

    companion object {
        fun fromRaw(raw: String?): UnitSystem = entries.firstOrNull { it.raw == raw } ?: METRIC
    }
}

/** Temperature display unit, overridable independently of [UnitSystem]. */
enum class TemperatureUnit(val raw: String) {
    CELSIUS("celsius"),
    FAHRENHEIT("fahrenheit");

    companion object {
        fun fromRaw(raw: String?): TemperatureUnit? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * How the Effort score is displayed. Choop stores Effort 0-100; people coming from WHOOP often
 * think in its 0-21 Day Strain axis, so this purely cosmetic toggle lets the SAME stored value be
 * shown on either scale. Default is Choop's own 0-100.
 */
enum class EffortScale(val raw: String) {
    HUNDRED("hundred"),
    WHOOP("whoop");

    companion object {
        fun fromRaw(raw: String?): EffortScale = entries.firstOrNull { it.raw == raw } ?: HUNDRED
    }
}

/**
 * Reads the two unit preferences from [NoopPrefs] and resolves the "match the system" default for
 * temperature. Preferences aren't reactive, so Compose screens read these once into remembered
 * state and re-read on a recomposition triggered by the Settings write.
 */
object UnitPrefs {
    /** The length/mass system (default Metric). */
    fun system(): UnitSystem =
        UnitSystem.fromRaw(NoopPrefs.getString(NoopPrefs.KEY_UNIT_SYSTEM, null))

    /** The resolved temperature unit, applying the "match the length/mass system" default. */
    fun temperature(): TemperatureUnit {
        val override = TemperatureUnit.fromRaw(
            NoopPrefs.getString(NoopPrefs.KEY_TEMPERATURE_UNIT, null),
        )
        return override ?: system().temperatureMatching
    }

    /** Pure resolver shared with the tests: explicit override wins, else follow the system. */
    fun resolveTemperature(system: UnitSystem, override: String?): TemperatureUnit =
        TemperatureUnit.fromRaw(override) ?: system.temperatureMatching

    /** SharedPreferences key for the Effort display scale. */
    const val KEY_EFFORT_SCALE = "effort.scale"

    /** The Effort display scale (default 0-100). Read once into Compose state like the other prefs. */
    fun effortScale(): EffortScale =
        EffortScale.fromRaw(NoopPrefs.getString(KEY_EFFORT_SCALE, null))

    /** Persist the Effort display scale. */
    fun setEffortScale(scale: EffortScale) {
        NoopPrefs.edit().putString(KEY_EFFORT_SCALE, scale.raw).apply()
    }
}

/**
 * Pure, platform-free unit conversion and display formatting. Every site that prints a distance,
 * mass, height or temperature goes through here so the unit toggle reaches all of them at once.
 * Nothing here reads preferences: callers pass the resolved [UnitSystem] / [TemperatureUnit] in,
 * which keeps the formatter trivially testable and side-effect free.
 */
object UnitFormatter {

    // MARK: Factors (single source of truth)

    /** 1 kilometre = 0.621371 miles. */
    const val MILES_PER_KILOMETER = 0.621371
    /** 1 kilogram = 2.20462 pounds. */
    const val POUNDS_PER_KILOGRAM = 2.20462
    /** 1 inch = 2.54 cm exactly. */
    const val CENTIMETERS_PER_INCH = 2.54

    // MARK: Distance (stored km)

    /** km -> miles. */
    fun kmToMiles(km: Double): Double = km * MILES_PER_KILOMETER

    /**
     * Format a distance given in METRES (the stored unit for workout distance).
     * Metric: "1.2 km" / "850 m". Imperial: "0.7 mi" / "230 yd" for sub-mile distances.
     */
    fun distanceFromMeters(meters: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> {
            val km = meters / 1000.0
            if (km >= 1) oneDecimal(km) + " km" else "${meters.roundToInt()} m"
        }
        UnitSystem.IMPERIAL -> {
            val miles = kmToMiles(meters / 1000.0)
            if (miles >= 0.1) {
                oneDecimal(miles) + " mi"
            } else {
                "${(meters * 1.09361).roundToInt()} yd"
            }
        }
    }

    /**
     * Format a distance given in KILOMETRES, with one decimal and a unit label.
     * Metric: "12.4 km". Imperial: "7.7 mi".
     */
    fun distanceFromKilometers(km: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> oneDecimal(km) + " km"
        UnitSystem.IMPERIAL -> oneDecimal(kmToMiles(km)) + " mi"
    }

    /** Unit label only, for sites that format the number separately. "km" / "mi". */
    fun distanceUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "mi" else "km"

    // MARK: Mass (stored kg)

    /** kg -> pounds. */
    fun kgToPounds(kg: Double): Double = kg * POUNDS_PER_KILOGRAM

    /** Format a mass given in KILOGRAMS with one decimal + unit. Metric: "74.5 kg". Imperial: "164.2 lb". */
    fun massFromKilograms(kg: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> oneDecimal(kg) + " kg"
        UnitSystem.IMPERIAL -> oneDecimal(kgToPounds(kg)) + " lb"
    }

    /** Mass unit label only. "kg" / "lb". */
    fun massUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "lb" else "kg"

    // MARK: Height (stored cm)

    /** cm -> total inches. */
    fun cmToInches(cm: Double): Double = cm / CENTIMETERS_PER_INCH

    /** Decompose a height in CENTIMETRES into whole feet + inches (inches rounded, carried into feet). */
    fun cmToFeetInches(cm: Double): Pair<Int, Int> {
        val totalInches = cmToInches(cm).roundToInt()
        var feet = totalInches / 12
        var inches = totalInches % 12
        if (inches == 12) { feet += 1; inches = 0 }
        return Pair(feet, inches)
    }

    /** Format a height given in CENTIMETRES. Metric: "178 cm". Imperial: "5' 10"". */
    fun heightFromCentimeters(cm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "${cm.roundToInt()} cm"
        UnitSystem.IMPERIAL -> {
            val (ft, inch) = cmToFeetInches(cm)
            "$ft' $inch\""
        }
    }

    // MARK: Temperature (stored degC — absolute)

    /** degC -> degF: F = C * 9/5 + 32. */
    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0

    /** Format an ABSOLUTE temperature in CELSIUS. Metric: "33.4 degC". Imperial: "92.1 degF". */
    fun temperatureFromCelsius(c: Double, unit: TemperatureUnit, decimals: Int = 1): String = when (unit) {
        TemperatureUnit.CELSIUS -> decimalString(c, decimals) + " °C"
        TemperatureUnit.FAHRENHEIT -> decimalString(celsiusToFahrenheit(c), decimals) + " °F"
    }

    /**
     * Format a temperature DEVIATION (a +/-delta degC, e.g. the skin-temp deviation pipeline). A delta
     * scales by 9/5 but does NOT add the +32 offset.
     */
    fun temperatureDeltaFromCelsius(dc: Double, unit: TemperatureUnit, decimals: Int = 1): String = when (unit) {
        TemperatureUnit.CELSIUS -> decimalString(dc, decimals) + " °C"
        TemperatureUnit.FAHRENHEIT -> decimalString(dc * 9.0 / 5.0, decimals) + " °F"
    }

    /** Temperature unit label only. "degC" / "degF". */
    fun temperatureUnit(unit: TemperatureUnit): String =
        if (unit == TemperatureUnit.FAHRENHEIT) "°F" else "°C"

    // MARK: Effort scale (stored 0-100)

    /** Exact inverse of the import boundary's 0-100 -> 0-21 conversion. */
    const val EFFORT_SCALE_FACTOR = 21.0 / 100.0

    /** The stored 0-100 Effort value mapped onto the selected display scale (the raw number, no unit). */
    fun effortValue(value: Double, scale: EffortScale): Double =
        if (scale == EffortScale.WHOOP) value * EFFORT_SCALE_FACTOR else value

    /**
     * Format a stored 0-100 Effort value for display on the selected scale, to one decimal.
     */
    fun effortDisplay(value: Double, scale: EffortScale): String =
        oneDecimal(effortValue(value, scale))

    /** The "out of" denominator label for the selected Effort scale — "100" or "21". */
    fun effortScaleMax(scale: EffortScale): String =
        if (scale == EffortScale.WHOOP) "21" else "100"

    // MARK: Helpers

    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun decimalString(v: Double, decimals: Int): String =
        if (decimals == 0) "${v.roundToInt()}" else String.format(Locale.US, "%.${decimals}f", v)
}
