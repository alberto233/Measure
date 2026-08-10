package com.measure.core.geometry.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The correction, and the four ways of not getting one.
 *
 * Most of this file is about refusal, which is the point. A calibration that accepts whatever
 * it is given is worse than no calibration: it takes one noisy reading, or one typo, and
 * applies it permanently to every measurement the phone will ever make. The interesting
 * behaviour is which offers get turned down.
 */
class CalibrationTest {

    private fun calibration(measured: Double, actual: Double): Calibration {
        val outcome = Calibration.of(measured, actual)
        assertTrue(
            outcome is CalibrationOutcome.Calibrated,
            "Expected a calibration from $measured against $actual, got $outcome",
        )
        return (outcome as CalibrationOutcome.Calibrated).calibration
    }

    private fun refusal(measured: Double, actual: Double): Refusal {
        val outcome = Calibration.of(measured, actual)
        assertTrue(
            outcome is CalibrationOutcome.Refused,
            "Expected a refusal from $measured against $actual, got $outcome",
        )
        return (outcome as CalibrationOutcome.Refused).reason
    }

    @Test
    fun `a device reading long is scaled down`() {
        // A 2.00 m door measured as 2.10 m: the phone reads 5% long, so everything it says
        // has to shrink.
        val calibration = calibration(measured = 2.10, actual = 2.00)

        assertEquals(2.00, calibration.apply(2.10), 1e-9)
        assertTrue(calibration.scale < 1.0, "Reading long must scale down.")
    }

    @Test
    fun `a device reading short is scaled up`() {
        val calibration = calibration(measured = 3.00, actual = 3.10)

        assertEquals(3.10, calibration.apply(3.00), 1e-9)
        assertTrue(calibration.scale > 1.0, "Reading short must scale up.")
    }

    /**
     * The correction is a ratio, so it holds at every length.
     *
     * This is the whole justification for a single stored number. If the bias were additive —
     * always 3 cm long, whatever the distance — one factor would be the wrong model and
     * calibrating on a door would make a 5 m wall worse.
     */
    @Test
    fun `the correction is proportional, not a fixed offset`() {
        val calibration = calibration(measured = 2.10, actual = 2.00)

        assertEquals(1.0, calibration.apply(1.05), 1e-9)
        assertEquals(10.0, calibration.apply(10.5), 1e-9)
    }

    /** What the interface shows: how far off the phone is, not what to multiply by. */
    @Test
    fun `bias reports how far the device is out`() {
        val calibration = calibration(measured = 2.08, actual = 2.00)

        assertEquals(0.04, calibration.bias, 1e-9)
    }

    @Test
    fun `nothing is a length until it is positive`() {
        assertEquals(Refusal.NOT_A_LENGTH, refusal(measured = 0.0, actual = 2.0))
        assertEquals(Refusal.NOT_A_LENGTH, refusal(measured = 2.0, actual = 0.0))
        assertEquals(Refusal.NOT_A_LENGTH, refusal(measured = -2.0, actual = 2.0))
    }

    /**
     * A credit card cannot calibrate anything, whatever `docs/ACCURACY.md` M9 originally said.
     *
     * At 3 cm of noise on an 85 mm reference the measurement is ±35%. Any factor derived from
     * it is noise wearing a correction's clothes, and it would be applied to every wall.
     */
    @Test
    fun `a reference shorter than a metre is refused`() {
        assertEquals(Refusal.REFERENCE_TOO_SHORT, refusal(measured = 0.30, actual = 0.297))
        assertEquals(Refusal.REFERENCE_TOO_SHORT, refusal(measured = 0.90, actual = 0.85))
    }

    /**
     * The refusal that matters most, because it is the one a satisfied user triggers.
     *
     * Somebody measures their 2.00 m door, sees 2.02 m, and reaches for calibration. There is
     * nothing there: 2 cm is inside the margin the app told them to expect on the very first
     * card of the guidance deck. Storing it would make the next reading of the same door
     * 1.98 m and look like a bug.
     */
    @Test
    fun `a difference inside the stated margin is not a bias`() {
        assertEquals(Refusal.WITHIN_NOISE, refusal(measured = 2.02, actual = 2.00))
        assertEquals(Refusal.WITHIN_NOISE, refusal(measured = 4.97, actual = 5.00))
    }

    /** Just outside the noise band, and the same numbers a centimetre further apart. */
    @Test
    fun `the noise band has a far side`() {
        assertEquals(Refusal.WITHIN_NOISE, refusal(measured = 2.059, actual = 2.00))

        val calibration = calibration(measured = 2.07, actual = 2.00)
        assertTrue(calibration.scale < 1.0)
    }

    /**
     * Beyond a few percent it is not a device, it is a mistake.
     *
     * Typing 2.00 for a door measured at 2.20, or measuring the wrong thing entirely. A phone
     * whose scale is genuinely 10% out is broken in ways one multiplier will not rescue.
     */
    @Test
    fun `an implausible difference is refused rather than believed`() {
        assertEquals(Refusal.IMPLAUSIBLE, refusal(measured = 2.20, actual = 2.00))
        assertEquals(Refusal.IMPLAUSIBLE, refusal(measured = 5.00, actual = 3.00))
    }

    /** The identity is what an uncalibrated device carries, and it must change nothing. */
    @Test
    fun `no calibration changes no measurement`() {
        assertEquals(4.237, Calibration.NONE.apply(4.237), 1e-12)
        assertTrue(Calibration.NONE.isIdentity)
        assertEquals(0.0, Calibration.NONE.bias, 1e-12)
    }
}
