package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec3
import com.measure.core.units.Length

/**
 * A completed point-to-point measurement.
 *
 * [to] holds the *constrained* position, so the length is always the length that was
 * displayed and the raw offset is kept separately in [correction] rather than being
 * quietly discarded.
 */
data class MeasuredSegment(
    val id: Long,
    val from: SampledPoint,
    val to: SampledPoint,
    val mode: MeasurementMode,
    /** How far the mode moved the second point. Zero in [MeasurementMode.FREE]. */
    val correction: Double = 0.0,
) {
    val lengthMetres: Double get() = from.position.distanceTo(to.position)
    val length: Length get() = Length(lengthMetres)

    val sigmaMetres: Double get() = PointUncertainty.distanceSigma(from, to)
    val sigma: Length get() = Length(sigmaMetres)

    val midpoint: Vec3
        get() = Vec3(
            (from.position.x + to.position.x) / 2.0,
            (from.position.y + to.position.y) / 2.0,
            (from.position.z + to.position.z) / 2.0,
        )

    /** Relative tolerance, for deciding whether a measurement is worth trusting. */
    val relativeSigma: Double
        get() = if (lengthMetres < 1e-6) Double.POSITIVE_INFINITY else sigmaMetres / lengthMetres

    /**
     * Within the ±2% per-segment target from docs/ACCURACY.md §3. Short measurements are
     * judged on absolute error instead, because 2% of 10 cm is 2 mm and nothing on a
     * phone is that good.
     */
    val meetsAccuracyTarget: Boolean
        get() = sigmaMetres <= 0.02 || relativeSigma <= 0.02
}
