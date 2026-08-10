package com.traza.core.units

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns metres into the strings the user actually reads.
 *
 * Three decisions worth stating, because they are product decisions rather than
 * formatting details:
 *
 * 1. Metric output picks its own unit by magnitude, so a room reads "3.42 m" and a
 *    skirting gap reads "18 mm" without the user changing anything.
 * 2. Every measurement can be rendered with its uncertainty. This app is not a laser
 *    and says so; see docs/ACCURACY.md.
 * 3. Numbers are formatted in the reader's locale by default. Much of Europe writes
 *    "3,42 m", and an app whose entire purpose is displaying numbers has no business
 *    showing them in a foreign convention. Exporters must override this with
 *    [Locale.ROOT] — DXF, SVG and CSV all require a decimal point, and a comma would
 *    produce files that silently fail to parse.
 */
object LengthFormatter {

    /** Denominator for imperial fractions. Eighths are the joinery default. */
    const val DEFAULT_IMPERIAL_DENOMINATOR = 8

    fun format(
        length: Length,
        system: UnitSystem,
        imperialDenominator: Int = DEFAULT_IMPERIAL_DENOMINATOR,
        locale: Locale = Locale.getDefault(),
    ): String = when (system) {
        UnitSystem.METRIC -> formatMetric(length, locale)
        UnitSystem.IMPERIAL -> formatImperial(length, imperialDenominator, locale)
    }

    /**
     * Metric, choosing the unit by magnitude: millimetres below a centimetre,
     * centimetres below a metre, metres above.
     */
    fun formatMetric(length: Length, locale: Locale = Locale.getDefault()): String {
        val magnitude = abs(length.metres)
        return when {
            magnitude < 0.01 -> "${trimmed(length.millimetres, 0, locale)} mm"
            magnitude < 1.0 -> "${trimmed(length.centimetres, 1, locale)} cm"
            else -> "${trimmed(length.metres, 2, locale)} m"
        }
    }

    /**
     * Metric in one fixed unit, for tables and exports where a common unit reads better.
     *
     * Unlike the auto-scaling form this keeps trailing zeros: asking for three decimals
     * and getting two back would misalign a column and understate the stated precision.
     */
    fun formatMetric(
        length: Length,
        unit: LengthUnit,
        decimals: Int,
        locale: Locale = Locale.getDefault(),
    ): String {
        require(unit.system == UnitSystem.METRIC) { "$unit is not a metric unit" }
        return "${fixed(length.to(unit), decimals, locale)} ${unit.symbol}"
    }

    /**
     * Imperial as feet, inches and a fraction: `12' 4 3/8"`.
     *
     * Rounds to the nearest 1/[denominator] inch and carries properly, so 11.99 inches
     * at eighths becomes `1'` rather than `0' 12"`.
     */
    fun formatImperial(
        length: Length,
        denominator: Int = DEFAULT_IMPERIAL_DENOMINATOR,
        locale: Locale = Locale.getDefault(),
    ): String {
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
        // Imperial output is whole numbers and fractions, so the locale never affects
        // the digits. It is threaded through only so callers need not special-case it.
        @Suppress("UNUSED_EXPRESSION") locale
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
        locale: Locale = Locale.getDefault(),
    ): String {
        val value = format(length, system, imperialDenominator, locale)
        val tolerance = formatTolerance(sigma, system, imperialDenominator, locale)
        return "$value ±$tolerance"
    }

    private fun formatTolerance(
        sigma: Length,
        system: UnitSystem,
        imperialDenominator: Int,
        locale: Locale,
    ): String {
        val magnitude = abs(sigma.metres)
        return when (system) {
            UnitSystem.METRIC ->
                if (magnitude < 0.01) "${trimmed(sigma.millimetres, 0, locale)} mm"
                else "${trimmed(sigma.centimetres, 0, locale)} cm"

            UnitSystem.IMPERIAL -> formatImperial(sigma, imperialDenominator, locale)
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

    /** Fixed decimals, keeping trailing zeros. */
    internal fun fixed(value: Double, decimals: Int, locale: Locale): String =
        String.format(locale, "%.${decimals}f", value)

    /** Trims trailing zeros so lengths read "3.4 m" rather than "3.40 m". */
    internal fun trimmed(value: Double, decimals: Int, locale: Locale): String {
        val text = fixed(value, decimals, locale)
        val separator = decimalSeparator(locale)
        if (!text.contains(separator)) return text
        return text.trimEnd('0').trimEnd(separator)
    }

    /** Whatever this locale uses between the whole and fractional parts. */
    internal fun decimalSeparator(locale: Locale): Char =
        java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
}
