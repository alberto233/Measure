package com.measure.core.units

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LengthFormatterTest {

    @Test
    fun `metric picks its unit by magnitude`() {
        assertEquals("3.42 m", LengthFormatter.formatMetric(3.42.metres))
        assertEquals("85 cm", LengthFormatter.formatMetric(0.85.metres))
        assertEquals("8 mm", LengthFormatter.formatMetric(0.008.metres))
    }

    @Test
    fun `metric trims trailing zeros`() {
        assertEquals("3.4 m", LengthFormatter.formatMetric(3.40.metres))
        assertEquals("3 m", LengthFormatter.formatMetric(3.0.metres))
    }

    @Test
    fun `metric in a fixed unit`() {
        assertEquals("342 cm", LengthFormatter.formatMetric(3.42.metres, LengthUnit.CENTIMETRE, 0))
        assertEquals("3.420 m", LengthFormatter.formatMetric(3.42.metres, LengthUnit.METRE, 3))
    }

    @Test
    fun `imperial renders feet inches and a fraction`() {
        // 12' 4 3/8" is 148.375 inches
        val length = Length.of(148.375, LengthUnit.INCH)
        assertEquals("12' 4 3/8\"", LengthFormatter.formatImperial(length))
    }

    @Test
    fun `imperial omits parts that are zero`() {
        assertEquals("1'", LengthFormatter.formatImperial(Length.of(12.0, LengthUnit.INCH)))
        assertEquals("4\"", LengthFormatter.formatImperial(Length.of(4.0, LengthUnit.INCH)))
        assertEquals("3/8\"", LengthFormatter.formatImperial(Length.of(0.375, LengthUnit.INCH)))
        assertEquals("0\"", LengthFormatter.formatImperial(Length.ZERO))
    }

    @Test
    fun `imperial reduces fractions to lowest terms`() {
        // 4/8 must read as 1/2, not 4/8
        assertEquals("1/2\"", LengthFormatter.formatImperial(Length.of(0.5, LengthUnit.INCH)))
        assertEquals("1 1/4\"", LengthFormatter.formatImperial(Length.of(1.25, LengthUnit.INCH)))
    }

    @Test
    fun `imperial carries rounding up into the next foot`() {
        // 11.99 inches must become 1', never 0' 12"
        val length = Length.of(11.99, LengthUnit.INCH)
        assertEquals("1'", LengthFormatter.formatImperial(length))
    }

    @Test
    fun `imperial honours a finer denominator`() {
        val sixteenth = Length.of(0.0625, LengthUnit.INCH)
        assertEquals("1/16\"", LengthFormatter.formatImperial(sixteenth, denominator = 16))

        // A thirty-second is below half a tick at eighths, so it rounds away entirely.
        val thirtySecond = Length.of(0.03125, LengthUnit.INCH)
        assertEquals("0\"", LengthFormatter.formatImperial(thirtySecond, denominator = 8))
    }

    @Test
    fun `imperial handles negatives`() {
        assertEquals("-1'", LengthFormatter.formatImperial(Length.of(-12.0, LengthUnit.INCH)))
    }

    @Test
    fun `uncertainty is shown in a compact unit`() {
        val result = LengthFormatter.formatWithUncertainty(
            length = 3.42.metres,
            sigma = 0.03.metres,
            system = UnitSystem.METRIC,
        )
        assertEquals("3.42 m ±3 cm", result)
    }

    @Test
    fun `uncertainty falls to millimetres when small`() {
        val result = LengthFormatter.formatWithUncertainty(
            length = 3.42.metres,
            sigma = 0.004.metres,
            system = UnitSystem.METRIC,
        )
        assertEquals("3.42 m ±4 mm", result)
    }
}
