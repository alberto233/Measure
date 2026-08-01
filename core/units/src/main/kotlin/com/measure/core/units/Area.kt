package com.measure.core.units

import java.util.Locale
import kotlin.math.abs

/** An area, stored internally in square metres. */
@JvmInline
value class Area(val squareMetres: Double) : Comparable<Area> {

    val squareFeet: Double get() = squareMetres / SQUARE_METRES_PER_SQUARE_FOOT

    operator fun plus(other: Area) = Area(squareMetres + other.squareMetres)
    operator fun minus(other: Area) = Area(squareMetres - other.squareMetres)
    operator fun times(scalar: Double) = Area(squareMetres * scalar)

    override fun compareTo(other: Area): Int = squareMetres.compareTo(other.squareMetres)

    companion object {
        val ZERO = Area(0.0)

        const val SQUARE_METRES_PER_SQUARE_FOOT = 0.09290304

        fun ofSquareFeet(value: Double) = Area(value * SQUARE_METRES_PER_SQUARE_FOOT)
    }
}

operator fun Length.times(other: Length): Area = Area(metres * other.metres)

object AreaFormatter {

    fun format(
        area: Area,
        system: UnitSystem,
        locale: Locale = Locale.getDefault(),
    ): String = when (system) {
        UnitSystem.METRIC -> "${round(area.squareMetres, 2, locale)} m²"
        UnitSystem.IMPERIAL -> "${round(area.squareFeet, 1, locale)} ft²"
    }

    /**
     * An area with its uncertainty, as a range: `18.4–19.6 m²`.
     *
     * A range reads better than `19.0 ±0.6 m²` for areas, because the user is usually
     * about to buy something by the square metre and wants the upper figure.
     */
    fun formatRange(
        area: Area,
        sigma: Area,
        system: UnitSystem,
        locale: Locale = Locale.getDefault(),
    ): String {
        val low = Area(area.squareMetres - abs(sigma.squareMetres))
        val high = Area(area.squareMetres + abs(sigma.squareMetres))
        return when (system) {
            UnitSystem.METRIC ->
                "${round(low.squareMetres, 1, locale)}–${round(high.squareMetres, 1, locale)} m²"

            UnitSystem.IMPERIAL ->
                "${round(low.squareFeet, 0, locale)}–${round(high.squareFeet, 0, locale)} ft²"
        }
    }

    private fun round(value: Double, decimals: Int, locale: Locale): String =
        LengthFormatter.trimmed(value, decimals, locale)
}
