package com.measure.core.units

import java.util.Locale

/**
 * A volume, stored internally in cubic metres.
 *
 * Earns its place alongside [Length] and [Area] because a room's volume is what heating,
 * cooling and ventilation are sized from, and because the alternative — passing a bare
 * `Double` around — is exactly how a cubic metre ends up displayed as a square one.
 */
@JvmInline
value class Volume(val cubicMetres: Double) : Comparable<Volume> {

    val cubicFeet: Double get() = cubicMetres / CUBIC_METRES_PER_CUBIC_FOOT

    operator fun plus(other: Volume) = Volume(cubicMetres + other.cubicMetres)
    operator fun minus(other: Volume) = Volume(cubicMetres - other.cubicMetres)
    operator fun times(scalar: Double) = Volume(cubicMetres * scalar)

    override fun compareTo(other: Volume): Int = cubicMetres.compareTo(other.cubicMetres)

    companion object {
        val ZERO = Volume(0.0)

        /** A foot is exactly 0.3048 m, so this is exact rather than measured. */
        const val CUBIC_METRES_PER_CUBIC_FOOT = 0.028316846592
    }
}

operator fun Area.times(height: Length): Volume = Volume(squareMetres * height.metres)

object VolumeFormatter {

    fun format(
        volume: Volume,
        system: UnitSystem,
        locale: Locale = Locale.getDefault(),
    ): String = when (system) {
        UnitSystem.METRIC -> "${round(volume.cubicMetres, 1, locale)} m³"
        UnitSystem.IMPERIAL -> "${round(volume.cubicFeet, 0, locale)} ft³"
    }

    private fun round(value: Double, decimals: Int, locale: Locale): String =
        LengthFormatter.trimmed(value, decimals, locale)
}
