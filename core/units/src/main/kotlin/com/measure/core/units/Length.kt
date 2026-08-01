package com.measure.core.units

/**
 * A length, stored internally in metres.
 *
 * Everything in the app stores metres and formats at display time. Mixing storage
 * units is the most reliable way to produce unit bugs in a measuring app, so the
 * conversion boundary is kept at the edge, in [LengthFormatter] and [ImperialParser].
 */
@JvmInline
value class Length(val metres: Double) : Comparable<Length> {

    val millimetres: Double get() = metres / LengthUnit.MILLIMETRE.metres
    val centimetres: Double get() = metres / LengthUnit.CENTIMETRE.metres
    val inches: Double get() = metres / LengthUnit.INCH.metres
    val feet: Double get() = metres / LengthUnit.FOOT.metres

    fun to(unit: LengthUnit): Double = metres / unit.metres

    val absoluteValue: Length get() = Length(kotlin.math.abs(metres))

    operator fun plus(other: Length) = Length(metres + other.metres)
    operator fun minus(other: Length) = Length(metres - other.metres)
    operator fun times(scalar: Double) = Length(metres * scalar)
    operator fun div(scalar: Double) = Length(metres / scalar)
    operator fun div(other: Length): Double = metres / other.metres
    operator fun unaryMinus() = Length(-metres)

    override fun compareTo(other: Length): Int = metres.compareTo(other.metres)

    companion object {
        val ZERO = Length(0.0)

        fun of(value: Double, unit: LengthUnit) = Length(value * unit.metres)
    }
}

val Double.metres: Length get() = Length(this)
val Double.centimetres: Length get() = Length.of(this, LengthUnit.CENTIMETRE)
val Double.millimetres: Length get() = Length.of(this, LengthUnit.MILLIMETRE)
val Int.metres: Length get() = Length(toDouble())
