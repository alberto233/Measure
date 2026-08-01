package com.measure.core.units

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs

class LengthParserTest {

    private fun assertMetres(expected: Double, actual: Length?) {
        requireNotNull(actual) { "expected a parsed length, got null" }
        val delta = abs(expected - actual.metres)
        if (delta > 1e-9) {
            throw AssertionError("expected $expected m but was ${actual.metres} m")
        }
    }

    @Test
    fun `metric accepts explicit units`() {
        assertMetres(3.42, LengthParser.parseMetric("3.42 m"))
        assertMetres(3.42, LengthParser.parseMetric("342 cm"))
        assertMetres(3.42, LengthParser.parseMetric("3420 mm"))
    }

    @Test
    fun `metric treats a bare number as metres`() {
        assertMetres(3.42, LengthParser.parseMetric("3.42"))
    }

    @Test
    fun `metric accepts a comma decimal separator`() {
        assertMetres(3.42, LengthParser.parseMetric("3,42 m"))
    }

    @Test
    fun `metric rejects nonsense`() {
        assertNull(LengthParser.parseMetric(""))
        assertNull(LengthParser.parseMetric("wide"))
        assertNull(LengthParser.parseMetric("3.42 km"))
    }

    @Test
    fun `imperial accepts feet inches and a fraction`() {
        val expected = 148.375 * LengthUnit.INCH.metres
        assertMetres(expected, LengthParser.parseImperial("12' 4 3/8\""))
        assertMetres(expected, LengthParser.parseImperial("12'4 3/8\""))
    }

    @Test
    fun `imperial accepts feet only`() {
        assertMetres(12 * LengthUnit.FOOT.metres, LengthParser.parseImperial("12'"))
    }

    @Test
    fun `imperial accepts inches only`() {
        assertMetres(4.375 * LengthUnit.INCH.metres, LengthParser.parseImperial("4 3/8\""))
        assertMetres(0.375 * LengthUnit.INCH.metres, LengthParser.parseImperial("3/8\""))
    }

    @Test
    fun `imperial treats a bare number as inches`() {
        assertMetres(4 * LengthUnit.INCH.metres, LengthParser.parseImperial("4"))
    }

    @Test
    fun `imperial accepts word forms`() {
        val expected = 12 * LengthUnit.FOOT.metres + 4 * LengthUnit.INCH.metres
        assertMetres(expected, LengthParser.parseImperial("12 ft 4 in"))
        assertMetres(expected, LengthParser.parseImperial("12 feet 4 inches"))
    }

    @Test
    fun `imperial handles negatives`() {
        assertMetres(-12 * LengthUnit.FOOT.metres, LengthParser.parseImperial("-12'"))
    }

    @Test
    fun `imperial rejects nonsense`() {
        assertNull(LengthParser.parseImperial(""))
        assertNull(LengthParser.parseImperial("wide"))
        assertNull(LengthParser.parseImperial("3/0\""))
    }

    @Test
    fun `imperial round trips through the formatter`() {
        // Anything the formatter emits must parse back to the same length. This is the
        // property that matters in the editor: read a value, edit it, submit it.
        val samples = listOf(0.375, 4.0, 12.0, 148.375, 0.5, 100.125)
        for (inches in samples) {
            val original = Length.of(inches, LengthUnit.INCH)
            val text = LengthFormatter.formatImperial(original)
            val reparsed = LengthParser.parseImperial(text)
            assertMetres(original.metres, reparsed)
        }
    }

    @Test
    fun `parse dispatches on the unit system`() {
        assertMetres(3.42, LengthParser.parse("3.42", UnitSystem.METRIC))
        assertMetres(4 * LengthUnit.INCH.metres, LengthParser.parse("4", UnitSystem.IMPERIAL))
    }

    @Test
    fun `area formats as a range when uncertain`() {
        val result = AreaFormatter.formatRange(Area(19.0), Area(0.6), UnitSystem.METRIC)
        assertEquals("18.4–19.6 m²", result)
    }
}
