package com.traza.core.units

enum class UnitSystem {
    METRIC,
    IMPERIAL,
}

enum class LengthUnit(
    /** How many metres one of this unit is. */
    val metres: Double,
    val symbol: String,
    val system: UnitSystem,
) {
    MILLIMETRE(0.001, "mm", UnitSystem.METRIC),
    CENTIMETRE(0.01, "cm", UnitSystem.METRIC),
    METRE(1.0, "m", UnitSystem.METRIC),
    INCH(0.0254, "in", UnitSystem.IMPERIAL),
    FOOT(0.3048, "ft", UnitSystem.IMPERIAL),
    YARD(0.9144, "yd", UnitSystem.IMPERIAL),
}
