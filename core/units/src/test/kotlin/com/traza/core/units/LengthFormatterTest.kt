package com.traza.core.units

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Every assertion pins an explicit locale. The formatters default to the reader's
 * locale, which is right for the app and useless for a test — the same call would
 * produce "3.42 m" on one machine and "3,42 m" on another.
 */
class LengthFormatterTest {

    private val root = Locale.ROOT

    @Test
    fun `metric picks its unit by magnitude`() {
        assertEquals("3.42 m", LengthFormatter.formatMetric(3.42.metres, root))
        assertEquals("85 cm", LengthFormatter.formatMetric(0.85.metres, root))
        assertEquals("8 mm", LengthFormatter.formatMetric(0.008.metres, root))
    }

    @Test
    fun `metric trims trailing zeros`() {
        assertEquals("3.4 m", LengthFormatter.formatMetric(3.40.metres, root))
        assertEquals("3 m", LengthFormatter.formatMetric(3.0.metres, root))
    }

    @Test
    fun `metric in a fixed unit`() {
        assertEquals(
            "342 cm",
            LengthFormatter.formatMetric(3.42.metres, LengthUnit.CENTIMETRE, 0, root),
        )
        assertEquals(
            "3.420 m",
            LengthFormatter.formatMetric(3.42.metres, LengthUnit.METRE, 3, root),
        )
    }

    @Test
    fun `metric follows the reader's decimal separator`() {
        // Most of Europe writes 3,42 m. An app that exists to display numbers must not
        // show them in a foreign convention.
        assertEquals("3,42 m", LengthFormatter.formatMetric(3.42.metres, Locale.GERMANY))
        assertEquals("3,4 m", LengthFormatter.formatMetric(3.40.metres, Locale.GERMANY))
        assertEquals("85 cm", LengthFormatter.formatMetric(0.85.metres, Locale.GERMANY))
    }

    @Test
    fun `trailing zeros trim correctly under a comma locale`() {
        // Guards a real trap: trimming '.' from "3,40" would strip nothing and leave a
        // stray zero, so the trim has to follow the locale's separator.
        assertEquals("3,4 m", LengthFormatter.formatMetric(3.40.metres, Locale.GERMANY))
        assertEquals("3 m", LengthFormatter.formatMetric(3.0.metres, Locale.GERMANY))
    }

    @Test
    fun `exports can force a machine readable form`() {
        // DXF, SVG and CSV all require a decimal point. Locale.ROOT is how exporters ask
        // for one regardless of where the user is.
        assertEquals("3.42 m", LengthFormatter.formatMetric(3.42.metres, Locale.ROOT))
    }

    @Test
    fun `imperial renders feet inches and a fraction`() {
        val length = Length.of(148.375, LengthUnit.INCH)
        assertEquals("12' 4 3/8\"", LengthFormatter.formatImperial(length, locale = root))
    }

    @Test
    fun `imperial omits parts that are zero`() {
        assertEquals("1'", LengthFormatter.formatImperial(Length.of(12.0, LengthUnit.INCH), locale = root))
        assertEquals("4\"", LengthFormatter.formatImperial(Length.of(4.0, LengthUnit.INCH), locale = root))
        assertEquals("3/8\"", LengthFormatter.formatImperial(Length.of(0.375, LengthUnit.INCH), locale = root))
        assertEquals("0\"", LengthFormatter.formatImperial(Length.ZERO, locale = root))
    }

    @Test
    fun `imperial reduces fractions to lowest terms`() {
        assertEquals("1/2\"", LengthFormatter.formatImperial(Length.of(0.5, LengthUnit.INCH), locale = root))
        assertEquals("1 1/4\"", LengthFormatter.formatImperial(Length.of(1.25, LengthUnit.INCH), locale = root))
    }

    @Test
    fun `imperial carries rounding up into the next foot`() {
        // 11.99 inches must become 1', never 0' 12"
        val length = Length.of(11.99, LengthUnit.INCH)
        assertEquals("1'", LengthFormatter.formatImperial(length, locale = root))
    }

    @Test
    fun `imperial honours a finer denominator`() {
        val sixteenth = Length.of(0.0625, LengthUnit.INCH)
        assertEquals("1/16\"", LengthFormatter.formatImperial(sixteenth, denominator = 16, locale = root))

        // A thirty-second is below half a tick at eighths, so it rounds away entirely.
        val thirtySecond = Length.of(0.03125, LengthUnit.INCH)
        assertEquals("0\"", LengthFormatter.formatImperial(thirtySecond, denominator = 8, locale = root))
    }

    @Test
    fun `imperial handles negatives`() {
        assertEquals("-1'", LengthFormatter.formatImperial(Length.of(-12.0, LengthUnit.INCH), locale = root))
    }

    @Test
    fun `uncertainty is shown in a compact unit`() {
        val result = LengthFormatter.formatWithUncertainty(
            length = 3.42.metres,
            sigma = 0.03.metres,
            system = UnitSystem.METRIC,
            locale = root,
        )
        assertEquals("3.42 m ±3 cm", result)
    }

    @Test
    fun `uncertainty falls to millimetres when small`() {
        val result = LengthFormatter.formatWithUncertainty(
            length = 3.42.metres,
            sigma = 0.004.metres,
            system = UnitSystem.METRIC,
            locale = root,
        )
        assertEquals("3.42 m ±4 mm", result)
    }
}
