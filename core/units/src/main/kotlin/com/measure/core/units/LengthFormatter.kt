package com.measure.core.units

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns metres into the strings the user actually reads.
 *
 * Two decisions worth stating, because they are product decisions rather than
 * formatting details:
 *
 * 1. Metric output picks its own unit by magnitude, so a room reads "3.42 m" and a
 *    skirting gap reads "18 mm" without the user changing anything.
 * 2. Every measurement can be rendered with its uncertainty. This app is not a laser
 *    and says so; see docs/ACCURACY.md.
 */
object LengthFormatter {

    /** Denominator for imperial fractions. Eighths are the joinery default. */
    const val DEFAULT_IMPERIAL_DENOMINATOR = 8

    fun format(
        length: Length,
        system: UnitSystem,
        imperialDenominator: Int = DEFAULT_IMPERIAL_DENOMINATOR,
    ): String = when (system) {
        UnitSystem.METRIC -> formatMetric(length)
        UnitSystem.IMPERIAL -> formatImperial(length, imperialDenominator)
    }

    /**
     * Metric, choosing the unit by magnitude: millimetres below a centimetre,
     * centimetres below a metre, metres above.
     */
    fun formatMetric(length: Length): String {
        val magnitude = abs(length.metres)
        return when {
            magnitude < 0.01 -> "${round(length.millimetres, 0)} mm"
            magnitude < 1.0 -> "${round(length.centimetres, 1)} cm"
            else -> "${round(length.metres, 2)} m"
        }
    }

    /**
     * Metric in one fixed unit, for tables and exports where a common unit reads better.
     *
     * Unlike the auto-scaling form this keeps trailing zeros: asking for three decimals
     * and getting two back would misalign a column and understate the stated precision.
     */
    fun formatMetric(length: Length, unit: LengthUnit, decimals: Int): String {
        require(unit.system == UnitSystem.METRIC) { "$unit is not a metric unit" }
        return "${String.format(Locale.US, "%.${decimals}f", length.to(unit))} ${unit.symbol}"
    }

    /**
     * Imperial as feet, inches and a fraction: `12' 4 3/8"`.
     *
     * Rounds to the nearest 1/[denominator] inch and carries properly, so 11.99 inches
     * at eighths becomes `1'` rather than `0' 12"`.
     */
    fun formatImperial(length: Length, denominator: Int = DEFAULT_IMPERIAL_DENOMINATOR): String {
        require(denominator > 0) { "denominator must be positive" }

        val negative = length.metres < 0
        val totalTicks = (abs(length.inches) * denominator).roundToLong()

        val wholeInches = totalTicks / denominator
        val fractionTicks = (totalTicks % denominator).toInt()
        val feet = wholeInches / 12
        val inches = wholeInches % 12

        val parts = buildList {
            if (feet > 0) add("$feet'")
            val inchPart = buildInchPart(inches, fractionTicks, denominator)
            if (inchPart != null) add(inchPart)
        }

        val body = if (parts.isEmpty()) "0\"" else parts.joinToString(" ")
        return if (negative) "-$body" else body
    }

    /**
     * A measurement with its uncertainty, e.g. `3.42 m ±3 cm`.
     *
     * The uncertainty is rendered in a compact absolute unit rather than matching the
     * value's unit, because "±3 cm" is easier to judge at a glance than "±0.03 m".
     */
    fun formatWithUncertainty(
        length: Length,
        sigma: Length,
        system: UnitSystem,
        imperialDenominator: Int = DEFAULT_IMPERIAL_DENOMINATOR,
    ): String {
        val value = format(length, system, imperialDenominator)
        val tolerance = formatTolerance(sigma, system, imperialDenominator)
        return "$value ±$tolerance"
    }

    private fun formatTolerance(
        sigma: Length,
        system: UnitSystem,
        imperialDenominator: Int,
    ): String {
        val magnitude = abs(sigma.metres)
        return when (system) {
            UnitSystem.METRIC ->
                if (magnitude < 0.01) "${round(sigma.millimetres, 0)} mm"
                else "${round(sigma.centimetres, 0)} cm"

            UnitSystem.IMPERIAL -> formatImperial(sigma, imperialDenominator)
        }
    }

    private fun buildInchPart(inches: Long, fractionTicks: Int, denominator: Int): String? {
        if (inches == 0L && fractionTicks == 0) return null
        if (fractionTicks == 0) return "$inches\""

        val divisor = gcd(fractionTicks, denominator)
        val fraction = "${fractionTicks / divisor}/${denominator / divisor}"
        return if (inches == 0L) "$fraction\"" else "$inches $fraction\""
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Trims trailing zeros so lengths read "3.4 m" rather than "3.40 m". */
    private fun round(value: Double, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        if (!text.contains('.')) return text
        return text.trimEnd('0').trimEnd('.')
    }
}
