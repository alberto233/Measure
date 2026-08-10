package com.measure.core.geometry.capture

/**
 * Advice about how far away the thing being measured is.
 *
 * Error scales with range, and the classic beginner mistake is to stand in the doorway
 * and point at the far wall — which is precisely the shot that disappoints
 * (docs/ACCURACY.md M4). We warn rather than refuse, because a long shot is sometimes
 * the only one available.
 */
enum class RangeAdvice {
    TOO_CLOSE,

    /** The comfortable band. Says nothing, because an indicator always lit stops being read. */
    IDEAL,
    LONG,
    VERY_LONG,
}

object RangeGate {

    /** Below this ARCore's depth and plane fits are unreliable, and so is focus. */
    const val MINIMUM_METRES = 0.3

    /** The band the accuracy model is calibrated for. */
    const val COMFORTABLE_METRES = 4.0

    /** Beyond this, warn harder. */
    const val LONG_METRES = 8.0

    fun advise(rangeMetres: Double): RangeAdvice = when {
        rangeMetres < MINIMUM_METRES -> RangeAdvice.TOO_CLOSE
        rangeMetres <= COMFORTABLE_METRES -> RangeAdvice.IDEAL
        rangeMetres <= LONG_METRES -> RangeAdvice.LONG
        else -> RangeAdvice.VERY_LONG
    }
}
