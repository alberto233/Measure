package com.traza.core.geometry.capture

import kotlin.math.abs

/**
 * A per-device scale correction — `docs/ACCURACY.md` M9.
 *
 * ARCore's scale is supposed to be metrically correct, and mostly is, but a device can carry
 * a small systematic bias: every distance it reports comes out a consistent fraction long or
 * short. That is worth correcting because it is the one error that does *not* average away —
 * measure a wall ten times on a phone that reads 1% long and you get ten answers that are all
 * 1% long, and the plan is wrong by the same 1% everywhere.
 *
 * A single factor is the whole model. Distances scale, areas scale by its square, volumes by
 * its cube, and all of that falls out of scaling the captured points themselves.
 *
 * **Bias, not noise.** Calibration cannot make a measurement more repeatable; it moves the
 * middle of the distribution, not its width. A calibrated phone still reports ±2–3 cm, and
 * the interface must not imply otherwise — the temptation to sell calibration as "now it's
 * accurate" is exactly the overclaim `docs/PRODUCT_PLAN.md` §5 exists to prevent.
 *
 * **Which is why most attempts to calibrate are refused.** [of] takes what the app measured
 * and what the thing really is, and hands back a correction only when the difference between
 * them cannot be explained by ordinary measurement noise. Somebody who measures a 2.00 m door
 * as 2.01 m has not found a 0.5% bias; they have found one measurement, and storing it as a
 * permanent correction would bake a single noisy reading into every plan they ever make.
 */
@JvmInline
value class Calibration(val scale: Double) {

    /** A length as this device would have to report it to be right. */
    fun apply(metres: Double): Double = metres * scale

    val isIdentity: Boolean get() = scale == 1.0

    /** How far off the device is, as a signed fraction. Positive means it reads long. */
    val bias: Double get() = 1.0 / scale - 1.0

    companion object {
        /** An uncalibrated device, which is every device until somebody says otherwise. */
        val NONE = Calibration(1.0)

        /**
         * The largest bias worth believing, either way.
         *
         * ARCore's scale error is a fraction of a percent on a working device. A reading 5%
         * out is not a device that needs calibrating; it is a mis-measurement, a typo, or a
         * user measuring a different thing from the one they typed. Accepting it would turn
         * one bad afternoon into every plan being wrong by 5%.
         */
        const val LIMIT = 0.05

        /**
         * The shortest reference worth calibrating against.
         *
         * `docs/ACCURACY.md` M9 suggests "a sheet of A4, a credit card". That is wrong and
         * this constant is the correction: a measurement carries roughly [NOISE] of
         * uncertainty regardless of how long the thing is, so on a 210 mm sheet of A4 the
         * noise is 15% of the answer and any bias under 15% is invisible beneath it. A door
         * is the shortest thing in an ordinary room that is long enough to be any use, and a
         * tape run across a room is better.
         */
        const val MINIMUM_REFERENCE = 1.0

        /**
         * The uncertainty of a single app measurement, in metres, for the noise test.
         *
         * Deliberately the app's own public claim from `docs/ACCURACY.md` rather than
         * something derived per reading: this figure is what the user was told to expect, so
         * it is the right yardstick for deciding whether what they are looking at is a bias
         * or is the thing they were warned about.
         */
        const val NOISE = 0.03

        /**
         * Work out the correction, or refuse and say why.
         *
         * @param measured what the app reported, in metres.
         * @param actual what the thing really is, in metres.
         */
        fun of(measured: Double, actual: Double): CalibrationOutcome = when {
            measured <= 0.0 || actual <= 0.0 -> CalibrationOutcome.Refused(Refusal.NOT_A_LENGTH)

            actual < MINIMUM_REFERENCE -> CalibrationOutcome.Refused(Refusal.REFERENCE_TOO_SHORT)

            // Two sigma, which is the ordinary "could this be chance?" bar. Below it the
            // difference is indistinguishable from the noise the app already declares, and
            // there is nothing here to correct.
            abs(measured - actual) < 2 * NOISE -> CalibrationOutcome.Refused(Refusal.WITHIN_NOISE)

            abs(actual / measured - 1.0) > LIMIT -> CalibrationOutcome.Refused(Refusal.IMPLAUSIBLE)

            else -> CalibrationOutcome.Calibrated(Calibration(actual / measured))
        }
    }
}

/** What [Calibration.of] decided. */
sealed interface CalibrationOutcome {
    data class Calibrated(val calibration: Calibration) : CalibrationOutcome
    data class Refused(val reason: Refusal) : CalibrationOutcome
}

/**
 * Why a calibration was not stored.
 *
 * Each of these is a thing to say to the user rather than an error to swallow. Refusing
 * silently would leave somebody believing they had corrected their phone when they had not,
 * which is worse than never offering calibration at all.
 */
enum class Refusal {
    /** A zero, a negative, or something that did not parse as a measurement. */
    NOT_A_LENGTH,

    /** Shorter than [Calibration.MINIMUM_REFERENCE]; the noise would swamp the bias. */
    REFERENCE_TOO_SHORT,

    /** The two figures agree to within the app's own margin. There is no bias to correct. */
    WITHIN_NOISE,

    /** Further apart than [Calibration.LIMIT]. Not a bias — a mistake. */
    IMPLAUSIBLE,
}
