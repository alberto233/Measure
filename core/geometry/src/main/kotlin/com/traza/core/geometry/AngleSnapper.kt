package com.traza.core.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Snaps wall bearings to a room's rectilinear frame.
 *
 * The highest-leverage correction available to us (docs/ACCURACY.md, M6). Domestic
 * rooms are overwhelmingly built to right angles, so a wall measured at 87.4° is
 * almost certainly a 90° wall carrying 2.6° of error. Snapping drives that error to
 * zero along the dominant axes rather than merely hiding it.
 */
class AngleSnapper(
    /** How far a wall may deviate and still be treated as intentional. */
    val tolerance: Double = DEFAULT_TOLERANCE,
    /** Whether 45° walls snap too, or only 90°. */
    val allowDiagonals: Boolean = false,
) {

    fun snap(polygon: Polygon): SnapResult {
        val edges = polygon.edges
        val axis = estimateAxis(edges)
        val step = if (allowDiagonals) PI / 4 else PI / 2

        val snaps = edges.map { edge ->
            val relative = edge.bearing - axis
            val multiple = (relative / step).roundToInt()
            val target = axis + multiple * step
            val deviation = normalise(edge.bearing - target)
            EdgeSnap(
                edgeIndex = edge.fromIndex,
                measuredBearing = edge.bearing,
                snappedBearing = target,
                isSnapped = abs(deviation) <= tolerance,
                deviation = deviation,
            )
        }
        return SnapResult(axisBearing = axis, snaps = snaps)
    }

    private fun estimateAxis(edges: List<Polygon.Edge>): Double =
        axisOf(edges.map { WallBearing(it.bearing, it.length) })

    companion object {
        /** Six degrees. Loose enough for hand-held capture, tight enough to respect a real bay. */
        val DEFAULT_TOLERANCE = Math.toRadians(6.0)

        /**
         * The dominant axis of a set of walls, modulo 90°, as a length-weighted circular mean.
         *
         * Taking the longest wall alone would work, but it throws away the evidence of every
         * other wall. Mapping each bearing θ to 4θ folds the four-fold symmetry of a
         * rectilinear frame onto the full circle, where a circular mean is well defined;
         * dividing by four maps it back. Long walls dominate, which is what we want, since
         * a long wall's bearing is measured far more precisely than a short one's.
         *
         * Exposed, and taking bearings rather than [Polygon.Edge], because capture needs the
         * same frame from an *open* chain of corners — there is no polygon yet while the user
         * is still walking the room. Two implementations of this would be two definitions of
         * which way the room faces, and a live snap that disagreed with the solve it is
         * previewing would be worse than no snap at all.
         */
        fun axisOf(walls: List<WallBearing>): Double {
            var sumX = 0.0
            var sumY = 0.0
            for (wall in walls) {
                val folded = 4.0 * wall.bearing
                sumX += wall.length * cos(folded)
                sumY += wall.length * sin(folded)
            }
            if (abs(sumX) < Vec2.EPSILON && abs(sumY) < Vec2.EPSILON) return 0.0
            return atan2(sumY, sumX) / 4.0
        }

        /** Folds an angle into (-PI, PI]. */
        fun normalise(angle: Double): Double {
            var a = angle
            while (a > PI) a -= 2 * PI
            while (a <= -PI) a += 2 * PI
            return a
        }
    }
}

/** A wall reduced to what [AngleSnapper.axisOf] needs: which way it runs, and how much it counts. */
data class WallBearing(val bearing: Double, val length: Double)

data class EdgeSnap(
    val edgeIndex: Int,
    val measuredBearing: Double,
    /** The rectilinear bearing this edge would be pulled to. */
    val snappedBearing: Double,
    val isSnapped: Boolean,
    /** Signed radians between the measured and snapped bearing. */
    val deviation: Double,
)

data class SnapResult(
    /** The room's frame, in radians, modulo 90°. */
    val axisBearing: Double,
    val snaps: List<EdgeSnap>,
) {
    val snappedCount: Int get() = snaps.count { it.isSnapped }
}
