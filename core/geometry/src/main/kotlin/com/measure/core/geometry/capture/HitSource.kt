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
 *
 * [spatialCorrelation] is how much of that error two nearby points from this kind of
 * source *share*. It matters because a measurement is a difference, and whatever the two
 * endpoints get wrong in common cancels out of a difference. A fitted plane is the
 * extreme case: if ARCore places the floor a centimetre low, every point on it is a
 * centimetre low, and the distance between two of them is unaffected. A depth hit shares
 * less, because much of its error is per-pixel. A lone feature point shares almost
 * nothing.
 */
enum class HitSource(
    val baseSigma: Double,
    val sigmaPerMetre: Double,
    val spatialCorrelation: Double,
    val label: String,
) {
    PLANE_POLYGON(0.008, 0.004, 0.80, "surface"),
    PLANE_INFINITE(0.015, 0.008, 0.75, "surface (extended)"),
    DEPTH(0.020, 0.012, 0.50, "depth"),

    /**
     * Derived from gravity rather than from any surface: the point where the line of
     * sight passes closest to the vertical through the first point.
     *
     * Needs nothing to hit, which is the entire reason it exists — a plain white ceiling
     * is the surface ARCore is worst at and precisely what a room height is measured to.
     * The height itself comes from the accelerometer and is very good; the error is in
     * where along that vertical the aim lands, so it grows with range like everything
     * else.
     */
    PLUMB(0.024, 0.016, 0.40, "plumb"),
    FEATURE_POINT(0.030, 0.020, 0.20, "feature"),
    INSTANT_PLACEMENT(0.060, 0.040, 0.60, "estimate"),
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
