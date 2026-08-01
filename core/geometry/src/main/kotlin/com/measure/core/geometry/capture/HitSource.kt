package com.measure.core.geometry.capture

/**
 * What kind of surface estimate produced a point, ordered best first.
 *
 * This is the ranking from docs/ACCURACY.md M1, expressed as data so the ordering and
 * the error model live together and cannot drift apart. A hit inside a tracked plane's
 * polygon is a model fitted over many observations and beats everything else; the
 * plane's infinite extension is the same model extrapolated; a depth hit is a per-pixel
 * estimate; a feature point is a single sparse landmark; instant placement is a guess at
 * scale that ARCore refines later.
 *
 * [baseSigma] and [sigmaPerMetre] are the two terms of a linear error model, in metres.
 * They are deliberately conservative: over-reporting confidence is the failure mode this
 * app exists to avoid.
 */
enum class HitSource(
    val baseSigma: Double,
    val sigmaPerMetre: Double,
    val label: String,
) {
    PLANE_POLYGON(0.008, 0.004, "surface"),
    PLANE_INFINITE(0.015, 0.008, "surface (extended)"),
    DEPTH(0.020, 0.012, "depth"),
    FEATURE_POINT(0.030, 0.020, "feature"),
    INSTANT_PLACEMENT(0.060, 0.040, "estimate"),
    ;

    /**
     * Whether this is good enough for a structural point such as a room corner.
     * A corner that is not on a detected plane is almost always a mis-tap
     * (docs/ACCURACY.md M1), so room capture may demand this even though a one-off
     * distance measurement need not.
     */
    val isStructural: Boolean get() = this == PLANE_POLYGON

    /** Expected standard deviation of a single observation at [range] metres. */
    fun sigmaAt(range: Double): Double = baseSigma + sigmaPerMetre * range.coerceAtLeast(0.0)
}
