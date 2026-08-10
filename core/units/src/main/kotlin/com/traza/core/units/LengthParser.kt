package com.traza.core.units

/**
 * Parses lengths the user types.
 *
 * This exists because of one specific feature: in the plan editor you can tape measure
 * a single wall, type its true length, and the constraint solver tightens the whole
 * plan around that certainty (docs/ACCURACY.md, M8). That makes typed input load
 * bearing rather than cosmetic, so the parser is permissive about format and strict
 * about returning null when it genuinely cannot tell what was meant.
 */
object LengthParser {

    /** Parses in the given system. Returns null if the text is not a length. */
    fun parse(text: String, system: UnitSystem): Length? = when (system) {
        UnitSystem.METRIC -> parseMetric(text)
        UnitSystem.IMPERIAL -> parseImperial(text)
    }

    /**
     * Accepts `3.42 m`, `342 cm`, `3420 mm`, and a bare `3.42` (metres).
     */
    fun parseMetric(text: String): Length? {
        val normalised = text.trim().lowercase().replace(",", ".")
        if (normalised.isEmpty()) return null

        val match = METRIC_PATTERN.matchEntire(normalised) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = when (match.groupValues[2]) {
            "mm" -> LengthUnit.MILLIMETRE
            "cm" -> LengthUnit.CENTIMETRE
            "m", "" -> LengthUnit.METRE
            else -> return null
        }
        return Length.of(value, unit)
    }

    /**
     * Accepts `12' 4 3/8"`, `12'4"`, `12'`, `4 3/8"`, `3/8"`, `12 ft 4 in`, and a
     * bare `4` (inches).
     */
    fun parseImperial(text: String): Length? {
        var rest = text.trim().lowercase().replace(",", ".")
        if (rest.isEmpty()) return null

        val negative = rest.startsWith("-")
        if (negative) rest = rest.removePrefix("-").trim()

        // Normalise word forms to symbols. Longest first, so "feet" is not left as "f".
        rest = rest
            .replace("feet", "'").replace("foot", "'").replace("ft", "'")
            .replace("inches", "\"").replace("inch", "\"").replace("in", "\"")

        var feet = 0.0
        val feetMatch = FEET_PATTERN.find(rest)
        if (feetMatch != null) {
            feet = feetMatch.groupValues[1].toDoubleOrNull() ?: return null
            rest = rest.removeRange(feetMatch.range).trim()
        }

        rest = rest.replace("\"", "").trim()

        val inches = if (rest.isEmpty()) {
            // Nothing after the feet part. Only valid if we actually saw feet.
            if (feetMatch == null) return null else 0.0
        } else {
            parseInchExpression(rest) ?: return null
        }

        val total = Length.of(feet, LengthUnit.FOOT) + Length.of(inches, LengthUnit.INCH)
        return if (negative) -total else total
    }

    /** Handles `4`, `4.5`, `4 3/8` and `3/8`. */
    private fun parseInchExpression(text: String): Double? {
        val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }
        return when (tokens.size) {
            1 -> if (tokens[0].contains('/')) parseFraction(tokens[0]) else tokens[0].toDoubleOrNull()
            2 -> {
                val whole = tokens[0].toDoubleOrNull() ?: return null
                val fraction = parseFraction(tokens[1]) ?: return null
                whole + fraction
            }
            else -> null
        }
    }

    private fun parseFraction(token: String): Double? {
        val parts = token.split('/')
        if (parts.size != 2) return null
        val numerator = parts[0].toDoubleOrNull() ?: return null
        val denominator = parts[1].toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    private val METRIC_PATTERN = Regex("""^(-?\d*\.?\d+)\s*(mm|cm|m)?$""")
    private val FEET_PATTERN = Regex("""(\d*\.?\d+)\s*'""")
    private val WHITESPACE = Regex("""\s+""")
}
