package com.traza.core.units

import java.util.Locale

/**
 * A liquid quantity, stored internally in litres.
 *
 * Separate from [Volume] on purpose, even though both are volumes. A room's volume is
 * reported in cubic metres and a tin of paint is not: "0.008 m³ of emulsion" is arithmetic
 * nobody can act on, and the whole point of the quantities view is to hand over a number
 * that can be taken into a shop. Keeping them as different types means the conversion has
 * to be written down once, here, rather than guessed at each call site.
 */
@JvmInline
value class Capacity(val litres: Double) : Comparable<Capacity> {

    val usGallons: Double get() = litres / LITRES_PER_US_GALLON

    operator fun plus(other: Capacity) = Capacity(litres + other.litres)
    operator fun times(scalar: Double) = Capacity(litres * scalar)

    override fun compareTo(other: Capacity): Int = litres.compareTo(other.litres)

    companion object {
        val ZERO = Capacity(0.0)

        /** Exact by definition of the US liquid gallon (231 in³). */
        const val LITRES_PER_US_GALLON = 3.785411784
    }
}

object CapacityFormatter {

    /**
     * One decimal, because a coverage rate printed on a tin does not support two.
     *
     * The generosity that a shopping quantity needs — a litre short is a second trip and a
     * batch that will not match — belongs in the calculation rather than here, so that a
     * formatter never quietly changes a number.
     */
    fun format(
        capacity: Capacity,
        system: UnitSystem,
        locale: Locale = Locale.getDefault(),
    ): String = "${value(capacity, system, locale)} ${unit(system)}"

    /** The number and the unit apart, so a reading can set them at different sizes. */
    fun value(
        capacity: Capacity,
        system: UnitSystem,
        locale: Locale = Locale.getDefault(),
    ): String = when (system) {
        UnitSystem.METRIC -> LengthFormatter.trimmed(capacity.litres, 1, locale)
        UnitSystem.IMPERIAL -> LengthFormatter.trimmed(capacity.usGallons, 1, locale)
    }

    fun unit(system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "L"
        UnitSystem.IMPERIAL -> "gal"
    }
}
