package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** One point observed on a candidate wall: where it lands on the plan, and how high it is. */
data class WallSample(val plan: Vec2, val height: Double)

/** A line fitted to [WallSample]s, with everything needed to judge whether to believe it. */
data class WallLine(
    val origin: Vec2,
    val direction: Vec2,
    val normal: Vec2,
    /** Spread of the inliers along the wall, in metres. */
    val extent: Double,
    /** Spread of the inliers in height. A wall has one; a floor does not. */
    val verticalSpread: Double,
    val inliers: Int,
    val total: Int,
    /** RMS distance of the inliers from the line, in metres. */
    val residual: Double,
)

/**
 * Fits a wall by looking at where a spray of points lands on the floor plan.
 *
 * This exists because ARCore will not fit a vertical plane to a plain painted wall, and
 * plain painted walls are most of the walls in most homes. Plane detection needs visual
 * features to track, and a white wall has none — which is precisely the room where the
 * corner is also hidden behind furniture, so wall-face capture is needed most exactly
 * where the mechanism it was built on stops working.
 *
 * The trick is that a vertical wall, seen from above, is a **line**. So rather than
 * fitting a plane in three dimensions and then checking it is upright, project every
 * observation straight onto the floor plan and fit a line in two. That enforces
 * verticality for free, halves the number of parameters, and is far better conditioned:
 * a set of points spread across a wall pins a 2D line tightly even when each individual
 * point is centimetres out.
 *
 * RANSAC rather than least squares, because the samples are not all on the wall. Some
 * land on the floor below it, on a picture frame, on a person walking past. Least squares
 * would let any of those drag the line; RANSAC finds the line most of the points agree on
 * and reports how many did, which is also the number that says whether to trust it.
 */
object WallLineFitter {

    /** Fewer points than this and there is nothing to be confident about. */
    const val MINIMUM_SAMPLES = 8

    /** How far off the line a point may be and still be counted as on the wall. */
    const val INLIER_TOLERANCE_METRES = 0.05

    /** Below this share agreeing, the view is not mostly one wall. */
    const val MINIMUM_INLIER_SHARE = 0.6

    /**
     * The floor test.
     *
     * A floor and a ceiling are also flat, also easy to hit, and seen edge-on they also
     * project to something line-shaped. What they cannot do is span a range of heights.
     * A quarter of a metre of vertical spread across the inliers is the cheapest reliable
     * way to insist that what was found is a wall.
     */
    const val MINIMUM_VERTICAL_SPREAD_METRES = 0.25

    /** Two points closer than this make a baseline too short to define a direction. */
    private const val MINIMUM_BASELINE_METRES = 0.2

    /** More than this and the pairwise search stops being free; the extra adds nothing. */
    private const val MAXIMUM_SAMPLES = 48

    fun fit(samples: List<WallSample>): WallLine? {
        val points = if (samples.size <= MAXIMUM_SAMPLES) {
            samples
        } else {
            // Evenly spread rather than the first N, so a truncated set still covers the
            // whole view instead of one corner of it.
            val step = samples.size.toDouble() / MAXIMUM_SAMPLES
            (0 until MAXIMUM_SAMPLES).map { samples[(it * step).toInt()] }
        }
        if (points.size < MINIMUM_SAMPLES) return null

        val seed = bestSeedLine(points) ?: return null
        // One refinement pass: fit properly to the seed's inliers, then re-select. More
        // passes chase their own tail on a set this small.
        val refined = totalLeastSquares(inliersOf(points, seed.first, seed.second)) ?: return null
        val inliers = inliersOf(points, refined.first, refined.second)

        val required = maxOf(MINIMUM_SAMPLES, (points.size * MINIMUM_INLIER_SHARE).toInt())
        if (inliers.size < required) return null

        val (origin, direction) = refined
        val normal = direction.perpendicular()

        val along = inliers.map { (it.plan - origin) dot direction }
        val heights = inliers.map { it.height }
        val residual = sqrt(inliers.sumOf { square((it.plan - origin) dot normal) } / inliers.size)

        return WallLine(
            origin = origin,
            direction = direction,
            normal = normal,
            extent = along.max() - along.min(),
            verticalSpread = heights.max() - heights.min(),
            inliers = inliers.size,
            total = points.size,
            residual = residual,
        )
    }

    /** The pair of points whose line the most other points agree with. */
    private fun bestSeedLine(points: List<WallSample>): Pair<Vec2, Vec2>? {
        var best: Pair<Vec2, Vec2>? = null
        var bestCount = 0
        var bestResidual = Double.MAX_VALUE

        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val from = points[i].plan
                val to = points[j].plan
                val span = to - from
                if (span.length < MINIMUM_BASELINE_METRES) continue

                val direction = span.normalised()
                val normal = direction.perpendicular()
                var count = 0
                var total = 0.0
                points.forEach { sample ->
                    val distance = abs((sample.plan - from) dot normal)
                    if (distance <= INLIER_TOLERANCE_METRES) {
                        count++
                        total += distance
                    }
                }
                // Ties broken on how tightly the agreeing points agree, so two lines with
                // the same support do not depend on iteration order.
                if (count > bestCount || (count == bestCount && total < bestResidual)) {
                    bestCount = count
                    bestResidual = total
                    best = from to direction
                }
            }
        }
        return best.takeIf { bestCount >= MINIMUM_SAMPLES }
    }

    private fun inliersOf(
        points: List<WallSample>,
        origin: Vec2,
        direction: Vec2,
    ): List<WallSample> {
        val normal = direction.perpendicular()
        return points.filter { abs((it.plan - origin) dot normal) <= INLIER_TOLERANCE_METRES }
    }

    /**
     * The line minimising perpendicular distance, by principal component analysis.
     *
     * Ordinary regression minimises vertical offsets and so depends on which way round
     * the axes happen to be, which for a wall that runs north-south rather than east-west
     * is the difference between a good fit and a divide by zero. The principal axis of
     * the covariance has no preferred direction and is exact in closed form for 2D.
     */
    private fun totalLeastSquares(points: List<WallSample>): Pair<Vec2, Vec2>? {
        if (points.size < 2) return null

        val centroid = points.fold(Vec2.ZERO) { sum, it -> sum + it.plan } / points.size.toDouble()
        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        points.forEach {
            val d = it.plan - centroid
            xx += d.x * d.x
            xy += d.x * d.y
            yy += d.y * d.y
        }

        val direction = if (abs(xy) < Vec2.EPSILON) {
            // Already axis-aligned; the larger variance is the wall's direction.
            if (xx >= yy) Vec2(1.0, 0.0) else Vec2(0.0, 1.0)
        } else {
            val largest = ((xx + yy) + hypot(xx - yy, 2.0 * xy)) / 2.0
            Vec2(xy, largest - xx).normalised()
        }
        if (direction.length < Vec2.EPSILON) return null

        return centroid to direction
    }

    private fun square(value: Double) = value * value
}
