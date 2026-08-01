package com.measure.core.geometry

/**
 * Distributes the drift revealed when a room capture returns to its starting corner.
 *
 * When the user re-taps the corner they began at, the second reading will not land on
 * the first. That gap *is* the accumulated tracking drift, handed to us as a directly
 * measured quantity — which is a gift, because it means we know the total error even
 * though we never knew the individual errors.
 *
 * The correction is the compass (Bowditch) rule from surveying: each vertex moves in
 * proportion to how far along the traverse it sits, so long walls absorb proportionally
 * more of the correction than short ones. See docs/ACCURACY.md, M7.
 */
object LoopClosure {

    /**
     * Above this fraction of the perimeter, a misclosure is not drift to be smeared
     * away but a sign that something went wrong, and the user should be told.
     */
    const val ACCEPTABLE_RELATIVE_ERROR = 0.02

    /**
     * @param measured corners in capture order, starting at the corner the user began on.
     * @param closingObservation the second reading of that starting corner, taken at the
     *   end of the walk. Pass null when the user did not re-tap, in which case there is
     *   no measured misclosure and the corners are returned unchanged.
     */
    fun adjust(measured: List<Vec2>, closingObservation: Vec2?): ClosureResult {
        require(measured.size >= 3) { "need at least 3 corners, got ${measured.size}" }

        if (closingObservation == null) {
            return ClosureResult(
                adjusted = measured,
                misclosure = Vec2.ZERO,
                perimeter = perimeterOf(measured, measured.first()),
                wasAdjusted = false,
            )
        }

        val misclosure = closingObservation - measured.first()

        // Cumulative traverse distance to each vertex. The first is zero by definition:
        // the starting corner is the one point we never move.
        val cumulative = DoubleArray(measured.size)
        for (i in 1 until measured.size) {
            cumulative[i] = cumulative[i - 1] + measured[i].distanceTo(measured[i - 1])
        }
        val total = cumulative.last() + closingObservation.distanceTo(measured.last())

        if (total < Vec2.EPSILON) {
            return ClosureResult(measured, misclosure, 0.0, wasAdjusted = false)
        }

        val adjusted = measured.mapIndexed { index, vertex ->
            vertex - misclosure * (cumulative[index] / total)
        }

        return ClosureResult(
            adjusted = adjusted,
            misclosure = misclosure,
            perimeter = total,
            wasAdjusted = true,
        )
    }

    private fun perimeterOf(vertices: List<Vec2>, closingPoint: Vec2): Double {
        var total = 0.0
        for (i in 1 until vertices.size) total += vertices[i].distanceTo(vertices[i - 1])
        return total + closingPoint.distanceTo(vertices.last())
    }
}

data class ClosureResult(
    val adjusted: List<Vec2>,
    val misclosure: Vec2,
    val perimeter: Double,
    val wasAdjusted: Boolean,
) {
    val misclosureDistance: Double get() = misclosure.length

    /** Misclosure as a fraction of the traverse length — the figure a surveyor quotes. */
    val relativeError: Double
        get() = if (perimeter < Vec2.EPSILON) 0.0 else misclosureDistance / perimeter

    /**
     * True when the drift is small enough to absorb silently. When false the capture
     * should be shown to the user rather than quietly corrected: a 15% misclosure means
     * something went wrong, and smoothing it away would be dishonest.
     */
    val isAcceptable: Boolean
        get() = relativeError <= LoopClosure.ACCEPTABLE_RELATIVE_ERROR
}
